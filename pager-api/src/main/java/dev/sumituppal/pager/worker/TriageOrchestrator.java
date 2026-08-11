package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.domain.Finding;
import dev.sumituppal.pager.domain.FindingRepository;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.Span;
import dev.sumituppal.pager.observability.SpanContext;
import dev.sumituppal.pager.specialist.SpecialistInput;
import dev.sumituppal.pager.specialist.SpecialistOutput;
import dev.sumituppal.pager.specialist.SymptomsSpecialist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The orchestrator: takes a job off the queue and drives one triage
 * from queued through the specialist analyses to completion.
 *
 * <h2>Current state (through PR #9)</h2>
 * <p>Runs the Symptoms specialist and persists its output as a
 * {@link Finding}. Later PRs will fan out to Change and Metrics
 * specialists in parallel (PR #10), then merge results via an
 * Aggregator (PR #11). For now, one specialist runs, one finding
 * gets saved, and its summary becomes the triage's aggregated summary.
 *
 * <h2>Why not @Transactional around the whole run?</h2>
 * <p>Historically we wrapped everything in one transaction. That's
 * fine when the enclosed work is 5ms of DB writes. But specialist
 * calls are 500-5000ms LLM calls — you cannot hold a database
 * transaction that long without exhausting the connection pool. So
 * the pattern now is: each state transition (queued → running →
 * completed) gets its own short transaction; the specialist calls
 * happen between transactions, outside any DB-transaction boundary.
 *
 * <h2>Specialist failures don't fail the triage</h2>
 * <p>{@link SymptomsSpecialist#analyze} never throws — it returns
 * {@link SpecialistOutput#unknown(String)} on any error. The
 * orchestrator persists that finding either way. This means a bad
 * LLM day produces a queryable failure record in {@code findings},
 * not a lost triage.
 */
@Component
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final TriageRunRepository triageRuns;
    private final FindingRepository findings;
    private final AgentEventEmitter events;
    private final SymptomsSpecialist symptoms;

    public TriageOrchestrator(
            TriageRunRepository triageRuns,
            FindingRepository findings,
            AgentEventEmitter events,
            SymptomsSpecialist symptoms) {
        this.triageRuns = triageRuns;
        this.findings = findings;
        this.events = events;
        this.symptoms = symptoms;
    }

    /**
     * Run one triage from queued → running → specialist analysis → completed.
     *
     * <p>This method is NOT @Transactional. Each state transition uses
     * a private helper that IS @Transactional, so DB writes still get
     * their commit boundaries — but LLM calls happen outside any
     * transaction to avoid holding DB connections during network I/O.
     */
    public void run(TriageJob job) {
        Optional<TriageRun> found = triageRuns.findById(job.triageId());
        if (found.isEmpty()) {
            log.warn("orchestrator asked to run triage {} but row not found — dropping",
                    job.triageId());
            return;
        }

        TriageRun triage = found.get();

        // Idempotency: skip triages that already reached a terminal state.
        if (triage.statusEnum() == TriageStatus.COMPLETED
                || triage.statusEnum() == TriageStatus.FAILED
                || triage.statusEnum() == TriageStatus.CANCELLED) {
            log.info("orchestrator skipping triage {} — already {}",
                    triage.getId(), triage.statusEnum());
            return;
        }

        SpanContext rootSpan = SpanContext.root(
                triage.getId(),
                Specialist.AGGREGATOR,
                "orchestrator"
        );

        try (Span span = Span.open(events, rootSpan)) {
            try {
                markRunning(triage);
                log.info("triage {} started", triage.getId());

                // Run the Symptoms specialist. Later PRs add Change +
                // Metrics in parallel via CompletableFuture.
                SpecialistInput input = new SpecialistInput(
                        triage.getId(),
                        triage.getIncidentId(),
                        triage.getAlertSummary(),
                        triage.getService(),
                        triage.severityEnum() != null
                                ? triage.severityEnum().name() : "UNKNOWN",
                        rootSpan
                );
                SpecialistOutput symptomsFinding = symptoms.analyze(input);

                // Persist the specialist's output as a Finding.
                persistFinding(triage, symptomsFinding);

                // For now, the Symptoms specialist's summary becomes
                // the triage's summary. When PR #11 lands, the Aggregator
                // will merge multiple findings into one summary here.
                markCompleted(triage, symptomsFinding.summary());
                log.info("triage {} completed", triage.getId());

                span.setOutcome("completed");
            } catch (RuntimeException e) {
                markFailed(triage);
                span.recordError(e);
                log.error("triage {} failed", triage.getId(), e);
                throw e;
            }
        }
    }

    // ---- transactional state transitions ----

    @Transactional
    protected void markRunning(TriageRun triage) {
        triage.statusEnum(TriageStatus.RUNNING);
        triage.setStartedAt(OffsetDateTime.now());
        triageRuns.save(triage);
    }

    @Transactional
    protected void persistFinding(TriageRun triage, SpecialistOutput output) {
        Finding finding = new Finding();
        finding.setTriageId(triage.getId());
        finding.specialistEnum(Specialist.SYMPTOMS);
        finding.categoryEnum(output.category());
        // Findings require a severity in the schema. We inherit the
        // triage's severity because Symptoms is describing the same
        // problem the alert reported.
        finding.severityEnum(triage.severityEnum() != null
                ? triage.severityEnum()
                : dev.sumituppal.pager.domain.Severity.P4);
        finding.setSummary(output.summary());
        finding.setConfidence(output.confidence());
        // Store the LLM's reasoning + raw response for audit.
        // SpecialistOutput.payload() is already a JSON string.
        finding.setRationale(output.payload());
        // createdAt is auto-populated in @PrePersist; no need to set explicitly.
        findings.save(finding);
    }

    @Transactional
    protected void markCompleted(TriageRun triage, String aggregatedSummary) {
        triage.setAggregatedSummary(aggregatedSummary);
        triage.statusEnum(TriageStatus.COMPLETED);
        triage.setCompletedAt(OffsetDateTime.now());
        triageRuns.save(triage);
    }

    @Transactional
    protected void markFailed(TriageRun triage) {
        triage.statusEnum(TriageStatus.FAILED);
        triage.setCompletedAt(OffsetDateTime.now());
        triageRuns.save(triage);
    }
}