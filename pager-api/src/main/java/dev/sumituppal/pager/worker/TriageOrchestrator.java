package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.Span;
import dev.sumituppal.pager.observability.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The orchestrator: takes a job off the queue and drives one triage to completion.
 *
 * <h2>Current state (PR #6 + #7)</h2>
 * <p>This is still a stub for specialist work — see the TODO block inside
 * {@link #run(TriageJob)}. What PR #7 adds is <strong>observability</strong>:
 * the orchestrator now wraps its work in a {@link Span} that emits
 * {@code span.start} / {@code span.end} rows to {@code agent_events}.
 * When the specialist fan-out lands in PR #10-12, each specialist gets its
 * own child span opened from this parent — and the whole triage becomes a
 * hierarchical trace you can visualize.
 *
 * <h2>Why is the emitter separate from the transaction?</h2>
 * <p>Event writes are side-effects. If the triage transaction rolls back
 * (e.g. because the DB briefly hiccups on the final commit), we still want
 * a record that the attempt happened. {@link AgentEventEmitter} swallows
 * write failures internally so an event blip can't fail a triage.
 *
 * <h2>Why {@code @Transactional} on the whole method still?</h2>
 * <p>Two writes into {@code triage_runs} (mark running, mark completed).
 * If the app crashes between them the triage would be stuck as "running
 * forever" — bad. Wrapping in a transaction means either both writes commit
 * or neither does. When the specialist calls become real LLM calls in PR
 * #10-12, this will need to be split: LLM calls take seconds and can't be
 * held inside a DB transaction. Each state transition will then get its own
 * short transaction. For now (stub), one transaction is fine.
 */
@Component
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final TriageRunRepository triageRuns;
    private final AgentEventEmitter events;

    public TriageOrchestrator(TriageRunRepository triageRuns, AgentEventEmitter events) {
        this.triageRuns = triageRuns;
        this.events = events;
    }

    /**
     * Run one triage from queued → running → completed.
     */
    @Transactional
    public void run(TriageJob job) {
        Optional<TriageRun> found = triageRuns.findById(job.triageId());
        if (found.isEmpty()) {
            log.warn("orchestrator asked to run triage {} but row not found — dropping",
                    job.triageId());
            return;
        }

        TriageRun triage = found.get();

        // Idempotency: if this triage has already reached a terminal state
        // (someone re-enqueued the same job, or a stale job survived a
        // restart), don't re-run.
        if (triage.statusEnum() == TriageStatus.COMPLETED
                || triage.statusEnum() == TriageStatus.FAILED
                || triage.statusEnum() == TriageStatus.CANCELLED) {
            log.info("orchestrator skipping triage {} — already {}",
                    triage.getId(), triage.statusEnum());
            return;
        }

        // Open the root span for this triage. Every specialist span later
        // will be a child of this one.
        SpanContext ctx = SpanContext.root(
                triage.getId(),
                Specialist.AGGREGATOR,
                "orchestrator"
        );

        try (Span span = Span.open(events, ctx)) {
            try {
                // Transition to running.
                triage.statusEnum(TriageStatus.RUNNING);
                triage.setStartedAt(OffsetDateTime.now());
                triageRuns.save(triage);
                log.info("triage {} started", triage.getId());

                // ==============================================================
                // TODO(PR #10-12): Fan out to specialists (Symptoms, Change,
                // Metrics, Comms) — each opened as a child span via
                // ctx.child(specialist, name). Merge findings via Aggregator.
                // HITL gate. For now: stub that immediately succeeds so we can
                // prove the transport end-to-end.
                // ==============================================================
                String placeholderSummary = "Triage stub — specialists will run here in PR #10-12.";
                triage.setAggregatedSummary(placeholderSummary);
                triage.statusEnum(TriageStatus.COMPLETED);
                triage.setCompletedAt(OffsetDateTime.now());
                triageRuns.save(triage);
                log.info("triage {} completed (stub)", triage.getId());

                span.setOutcome("completed");
            } catch (RuntimeException e) {
                // Mark the triage failed on any exception, then let the span
                // record the error before it closes.
                triage.statusEnum(TriageStatus.FAILED);
                triage.setCompletedAt(OffsetDateTime.now());
                triageRuns.save(triage);
                span.recordError(e);
                log.error("triage {} failed", triage.getId(), e);
                // Re-throw so the @Transactional boundary sees the failure.
                throw e;
            }
        }
    }
}