package dev.sumituppal.pager.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.ingress.WebhookIngressService.Result;
import dev.sumituppal.pager.security.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebhookIngressService}.
 *
 * <p>These tests use plain Mockito mocks — no Spring context. That means
 * each test runs in milliseconds and exercises the exact service logic
 * without JPA, Redis, or any autoconfigure surprises.
 *
 * <p>Coverage is by the four {@link Result} branches plus the race
 * condition on concurrent identical webhooks.
 */
class WebhookIngressServiceTest {

    private static final String SECRET = "test-secret-value";
    private static final String VALID_PAYLOAD =
        "{\"event\":{\"id\":\"e1\",\"event_type\":\"incident.triggered\"," +
        "\"data\":{\"id\":\"PGRXXXX\",\"title\":\"checkout 5xx spike\"," +
        "\"urgency\":\"high\",\"html_url\":\"https://ex.pd.com/i/1\"," +
        "\"service\":{\"id\":\"PS1\",\"summary\":\"checkout-api\"}}}}";

    private TriageRunRepository triageRuns;
    private TriageQueueProducer queue;
    private PagerProperties properties;
    private ObjectMapper objectMapper;
    private WebhookIngressService service;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        queue = mock(TriageQueueProducer.class);
        objectMapper = new ObjectMapper();
        properties = new PagerProperties(
            new java.math.BigDecimal("0.75"),
            45000L,
            15000L,
            new PagerProperties.Models("gpt-4o-mini", "gpt-4o", "text-embedding-3-small"),
            new java.math.BigDecimal("20.00"),
            "pager.triage.queue",
            SECRET);
        service = new WebhookIngressService(triageRuns, queue, properties, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────
    // Branch 1 — SignatureInvalid
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns SignatureInvalid when HMAC does not match")
    void invalidSignatureReturnsSignatureInvalid() {
        byte[] rawBody = VALID_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Result result = service.process(rawBody, "v1=" + "0".repeat(64));

        assertThat(result).isInstanceOf(Result.SignatureInvalid.class);
        verifyNoInteractions(triageRuns);
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("returns SignatureInvalid when header is null")
    void nullSignatureReturnsSignatureInvalid() {
        byte[] rawBody = VALID_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Result result = service.process(rawBody, null);

        assertThat(result).isInstanceOf(Result.SignatureInvalid.class);
        verifyNoInteractions(triageRuns);
    }

    // ─────────────────────────────────────────────────────────────
    // Branch 2 — MalformedPayload
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns MalformedPayload on unparseable JSON")
    void unparseableJsonReturnsMalformedPayload() throws Exception {
        byte[] rawBody = "not-json-at-all".getBytes(StandardCharsets.UTF_8);
        String sig = signatureFor(rawBody);

        Result result = service.process(rawBody, sig);

        assertThat(result).isInstanceOf(Result.MalformedPayload.class);
        verifyNoInteractions(triageRuns);
    }

    @Test
    @DisplayName("returns MalformedPayload when payload lacks event.data.id")
    void missingIncidentIdReturnsMalformedPayload() throws Exception {
        String noIncidentId = "{\"event\":{\"data\":{\"title\":\"has title but no id\"}}}";
        byte[] rawBody = noIncidentId.getBytes(StandardCharsets.UTF_8);
        String sig = signatureFor(rawBody);

        Result result = service.process(rawBody, sig);

        assertThat(result).isInstanceOf(Result.MalformedPayload.class);
    }

    // ─────────────────────────────────────────────────────────────
    // Branch 3 — Duplicate (fast path)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns Duplicate when idempotency key already exists")
    void duplicateReturnsDuplicate() throws Exception {
        byte[] rawBody = VALID_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String sig = signatureFor(rawBody);

        TriageRun existing = new TriageRun();
        existing.setId("triage_existing123");
        when(triageRuns.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        Result result = service.process(rawBody, sig);

        assertThat(result).isInstanceOf(Result.Duplicate.class);
        assertThat(((Result.Duplicate) result).triageId()).isEqualTo("triage_existing123");

        // Fast path — no save, no enqueue
        verify(triageRuns, times(0)).save(any());
        verifyNoInteractions(queue);
    }

    // ─────────────────────────────────────────────────────────────
    // Branch 4 — Accepted (happy path)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns Accepted, saves, and enqueues on new webhook")
    void newWebhookReturnsAccepted() throws Exception {
        byte[] rawBody = VALID_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String sig = signatureFor(rawBody);

        when(triageRuns.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(triageRuns.save(any())).thenAnswer(inv -> {
            TriageRun r = inv.getArgument(0);
            r.setId("triage_new789");
            return r;
        });

        Result result = service.process(rawBody, sig);

        assertThat(result).isInstanceOf(Result.Accepted.class);
        assertThat(((Result.Accepted) result).triageId()).isEqualTo("triage_new789");
        verify(triageRuns, times(1)).save(any());
        verify(queue, times(1)).enqueue(any());
    }

    // ─────────────────────────────────────────────────────────────
    // Race condition — concurrent identical webhooks
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("race with concurrent identical webhook resolves to Duplicate")
    void raceWithConcurrentWebhookIsDuplicate() throws Exception {
        byte[] rawBody = VALID_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String sig = signatureFor(rawBody);

        // First lookup: empty (we're the second one, but haven't seen the first yet)
        // Second lookup (after DataIntegrityViolation): finds the other's insert
        TriageRun other = new TriageRun();
        other.setId("triage_racewinner");

        when(triageRuns.findByIdempotencyKey(any()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(other));

        when(triageRuns.save(any()))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        Result result = service.process(rawBody, sig);

        assertThat(result).isInstanceOf(Result.Duplicate.class);
        assertThat(((Result.Duplicate) result).triageId()).isEqualTo("triage_racewinner");
    }

    // ─────────────────────────────────────────────────────────────
    // Helper — computes the signature the way PagerDuty would
    // ─────────────────────────────────────────────────────────────

    private static String signatureFor(byte[] rawBody) throws Exception {
        String hex = WebhookSignatureVerifier.computeSignatureHex(
            new String(rawBody, StandardCharsets.UTF_8), SECRET);
        return "v1=" + hex;
    }
}