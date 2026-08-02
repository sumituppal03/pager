package dev.sumituppal.pager.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.domain.Severity;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.security.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.Optional;

/**
 * Orchestrates one incoming PagerDuty webhook.
 *
 * <p>Flow:
 * <ol>
 *   <li>Verify the HMAC signature (401 on failure).</li>
 *   <li>Parse the payload into a normalized shape (400 on failure).</li>
 *   <li>Compute the idempotency key from {@code sha256(incidentId, payload)}.</li>
 *   <li>Check for an existing triage with that key — if found, return
 *       {@link Result.Duplicate}. This is the fast path.</li>
 *   <li>Otherwise insert a new {@code triage_runs} row and (after commit)
 *       enqueue a job to Redis, returning {@link Result.Accepted}.</li>
 * </ol>
 *
 * <p><strong>Race condition handling</strong>: two concurrent identical
 * webhooks could both pass the "already exists?" check before either
 * inserts. The Postgres unique constraint on {@code idempotency_key}
 * catches the second insert and throws {@link DataIntegrityViolationException},
 * which we treat as a late-arriving duplicate. This makes the check
 * <em>optimistic</em>: the fast path (existing row) is a cheap SELECT;
 * the correctness guarantee is at the DB level.
 *
 * <p><strong>Transactional outbox</strong>: the Redis enqueue is deferred
 * to {@code afterCommit} of the current DB transaction. If we enqueued
 * inline, the worker on its own connection could read the Redis job in
 * milliseconds — before our transaction commits — and its lookup of the
 * triage row would miss because the INSERT isn't yet visible on other
 * connections. Using {@link TransactionSynchronizationManager} guarantees
 * the enqueue runs exactly after the DB has durably committed. If the
 * transaction rolls back for any reason, the enqueue never runs — which
 * is correct: we don't want a triage job in Redis pointing at a row that
 * doesn't exist.
 */
@Service
public class WebhookIngressService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngressService.class);

    private final TriageRunRepository triageRuns;
    private final TriageQueueProducer queue;
    private final PagerProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookIngressService(
            TriageRunRepository triageRuns,
            TriageQueueProducer queue,
            PagerProperties properties,
            ObjectMapper objectMapper) {
        this.triageRuns = triageRuns;
        this.queue = queue;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Process one incoming webhook.
     *
     * @param rawBody   the raw request body bytes — same bytes that
     *                  were used to compute the HMAC signature
     * @param signature the value of the {@code X-PagerDuty-Signature} header
     * @return one of {@link Result.SignatureInvalid}, {@link Result.MalformedPayload},
     *         {@link Result.Duplicate}, or {@link Result.Accepted}
     */
    @Transactional
    public Result process(byte[] rawBody, String signature) {

        // Step 1 — HMAC verification. Fails closed on any doubt.
        String rawBodyString = new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        boolean valid = WebhookSignatureVerifier.verify(
                rawBodyString, signature, properties.pagerdutyWebhookSecret());
        if (!valid) {
            log.warn("rejecting webhook — invalid HMAC signature");
            return new Result.SignatureInvalid();
        }

        // Step 2 — parse the payload.
        PagerDutyWebhookRequest payload;
        try {
            payload = objectMapper.readValue(rawBody, PagerDutyWebhookRequest.class);
        } catch (Exception e) {
            log.warn("rejecting webhook — malformed JSON: {}", e.getMessage());
            return new Result.MalformedPayload(e.getMessage());
        }

        if (payload.incidentId() == null || payload.alertSummary() == null) {
            log.warn("rejecting webhook — payload missing required fields");
            return new Result.MalformedPayload("event.data.id or event.data.title missing");
        }

        // Step 3 — idempotency key.
        String key = IdempotencyKeyCalculator.compute(payload.incidentId(), rawBody);

        // Step 4 — fast path: already seen?
        Optional<TriageRun> existing = triageRuns.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("duplicate webhook — triage {} already exists for key {}",
                    existing.get().getId(), key);
            return new Result.Duplicate(existing.get().getId());
        }

        // Step 5 — insert, then enqueue after the DB commits.
        //
        // The enqueue MUST happen after the DB commit, not inside the transaction.
        // If we enqueue inline, the worker on its own connection reads the Redis
        // job in milliseconds — before our transaction commits — and its lookup
        // of the triage row misses because the INSERT isn't yet visible on other
        // connections. Result: "row not found — dropping."
        //
        // TransactionSynchronization.afterCommit runs the enqueue exactly once,
        // exactly after the DB has durably committed. If the transaction rolls
        // back for any reason, the enqueue never runs — which is correct: we
        // don't want a triage job in Redis pointing at a row that doesn't exist.
        TriageRun run = buildTriageRun(payload, rawBody, key);
        try {
            TriageRun saved = triageRuns.save(run);
            final String triageId = saved.getId();
            final String incidentId = payload.incidentId();

            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        queue.enqueue(TriageJob.firstAttempt(triageId, incidentId));
                    }
                }
            );

            log.info("accepted webhook — triage {} for incident {}",
                    triageId, incidentId);
            return new Result.Accepted(triageId);
        } catch (DataIntegrityViolationException e) {
            // Race with a concurrent identical webhook — the other one
            // won the insert. Treat as duplicate.
            Optional<TriageRun> raced = triageRuns.findByIdempotencyKey(key);
            if (raced.isPresent()) {
                log.info("race with concurrent webhook — triage {} already inserted",
                        raced.get().getId());
                return new Result.Duplicate(raced.get().getId());
            }
            // If we still can't find it, something is genuinely wrong.
            throw e;
        }
    }

    private TriageRun buildTriageRun(
            PagerDutyWebhookRequest payload, byte[] rawBody, String key) {
        TriageRun run = new TriageRun();
        run.setIdempotencyKey(key);
        run.setIncidentId(payload.incidentId());
        run.setIncidentUrl(payload.incidentUrl());
        run.setAlertSummary(payload.alertSummary());
        run.severityEnum(Severity.valueOf(payload.derivedSeverity()));
        run.setService(payload.serviceName());
        run.statusEnum(TriageStatus.QUEUED);
        run.setRawPayload(bytesAsJsonString(rawBody));
        return run;
    }

    private String bytesAsJsonString(byte[] raw) {
        // Store the raw payload as a JSON string for audit purposes.
        // The TriageRun.rawPayload column is JSONB, so Postgres will
        // parse and validate it on insert.
        try {
            // Re-emit through Jackson to normalize whitespace + prove parseability.
            // If PagerDuty ever sends valid JSON that Postgres won't accept as
            // JSONB, we'd fail here rather than at insert time.
            Object parsed = objectMapper.readValue(raw, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (IOException e) {
            // We already parsed the payload above; this is defensive.
            throw new IllegalStateException("unable to re-serialize payload", e);
        }
    }

    // -------------------------------------------------------------
    // Result — a sealed interface so callers must handle every case
    // -------------------------------------------------------------

    public sealed interface Result {

        /** Signature check failed. HTTP 401. */
        record SignatureInvalid() implements Result {}

        /** Payload was unparseable or missing required fields. HTTP 400. */
        record MalformedPayload(String reason) implements Result {}

        /** We've seen this exact webhook before. HTTP 200 with existing triage id. */
        record Duplicate(String triageId) implements Result {}

        /** New triage created and enqueued. HTTP 202 with new triage id. */
        record Accepted(String triageId) implements Result {}
    }
}