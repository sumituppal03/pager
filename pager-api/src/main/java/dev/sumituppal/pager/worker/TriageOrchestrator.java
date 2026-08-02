package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The orchestrator: takes a job off the queue and drives one triage to completion.
 *
 * <h2>Current state (PR #6)</h2>
 * <p>This is a <strong>stub</strong>. It picks up the triage row, marks it
 * running, then immediately marks it completed with a placeholder summary.
 * The real fan-out to specialist agents comes in later PRs (Symptoms in #9,
 * Change/Metrics in #10, Comms/Aggregator in #11).
 *
 * <h2>What this PR proves</h2>
 * <p>The end-to-end plumbing works: a webhook arrives → row in Postgres →
 * job on Redis → worker consumes → status transitions running → completed.
 * Once this pipeline is proven, the specialist PRs just fill in the middle
 * without touching the transport layer.
 *
 * <h2>Why {@code @Transactional} on the whole method?</h2>
 * <p>Two writes: mark running, then mark completed. If the app crashes
 * between them, the triage would be stuck as "running forever" — bad state.
 * Wrapping in a transaction means either both writes commit or neither does.
 * When we add specialist calls in PR #9, we'll split this: the specialist
 * fan-out runs OUTSIDE the transaction (because LLM calls take seconds and
 * we can't hold a DB transaction that long), and each state transition gets
 * its own short transaction. For now, one transaction is fine.
 *
 * <h2>What happens if the triage row doesn't exist?</h2>
 * <p>The worker still consumed the job, so Redis is happy. Postgres just
 * has no row to update. We log a warning and drop the job. This can only
 * happen if someone manually deleted a triage row while a job was in flight,
 * which shouldn't happen in normal operation.
 */
@Component
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final TriageRunRepository triageRuns;

    public TriageOrchestrator(TriageRunRepository triageRuns) {
        this.triageRuns = triageRuns;
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

        // Idempotency: if this triage has already completed (someone re-enqueued
        // the same job, or a stale job survived a restart), don't re-run.
        if (triage.statusEnum() == TriageStatus.COMPLETED
                || triage.statusEnum() == TriageStatus.FAILED
                || triage.statusEnum() == TriageStatus.CANCELLED) {
            log.info("orchestrator skipping triage {} — already {}",
                    triage.getId(), triage.statusEnum());
            return;
        }

        // Transition to running.
        triage.statusEnum(TriageStatus.RUNNING);
        triage.setStartedAt(OffsetDateTime.now());
        triageRuns.save(triage);
        log.info("triage {} started", triage.getId());

        try {
            // ================================================================
            // TODO(PR #9-11): Fan out to specialists (Symptoms, Change,
            // Metrics, Comms). Merge findings via Aggregator. HITL gate.
            // For now: stub that immediately succeeds so we can prove
            // the transport end-to-end.
            // ================================================================
            String placeholderSummary = "Triage stub — specialists will run here in PR #9-11.";
            triage.setAggregatedSummary(placeholderSummary);
            triage.statusEnum(TriageStatus.COMPLETED);
            triage.setCompletedAt(OffsetDateTime.now());
            triageRuns.save(triage);
            log.info("triage {} completed (stub)", triage.getId());
        } catch (Exception e) {
            log.error("triage {} failed", triage.getId(), e);
            triage.statusEnum(TriageStatus.FAILED);
            triage.setCompletedAt(OffsetDateTime.now());
            triageRuns.save(triage);
            // Re-throw so the @Transactional boundary rolls back appropriately
            // and the worker's outer catch can log at the correct level.
            throw e;
        }
    }
}