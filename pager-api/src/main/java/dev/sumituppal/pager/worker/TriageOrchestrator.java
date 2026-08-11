package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.config.PagerProperties;
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
import dev.sumituppal.pager.specialist.ChangeSpecialist;
import dev.sumituppal.pager.specialist.MetricsSpecialist;
import dev.sumituppal.pager.specialist.SpecialistAgent;
import dev.sumituppal.pager.specialist.SpecialistInput;
import dev.sumituppal.pager.specialist.SpecialistOutput;
import dev.sumituppal.pager.specialist.SymptomsSpecialist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The orchestrator: takes a job off the queue and drives one triage from
 * queued → running → parallel specialist fan-out → completed.
 *
 * <h2>Parallel fan-out</h2>
 * <p>PR #9 called one specialist sequentially. PR #10 (this PR) runs
 * three specialists — Symptoms, Change, Metrics — in parallel via
 * {@link CompletableFuture}. Each specialist call takes 5-10 seconds
 * against Groq; sequential execution would put a triage on the wall
 * clock at 15-30 seconds. Parallel execution completes at the pace of
 * the slowest specialist, cutting P99 latency by ~3x.
 *
 * <h2>Timeout per specialist, not global</h2>
 * <p>Each specialist gets its own {@code pager.specialist-timeout-ms}
 * budget (currently 45s). A slow Metrics specialist can't kill Change
 * and Symptoms. If a specialist times out, its slot returns an UNKNOWN
 * finding with the timeout details in the payload — persisted to the
 * DB same as any other result, so we can see WHICH specialists were
 * slow when investigating.
 *
 * <h2>Failure isolation</h2>
 * <p>Each specialist never throws (that contract is in
 * {@code AbstractLlmSpecialist}). But if the {@code CompletableFuture}
 * itself throws — timeout, thread interrupt, executor rejection — we
 * catch it in the join loop and record UNKNOWN for that specialist.
 * Two specialists can succeed even if a third dies.
 *
 * <h2>Why a dedicated executor?</h2>
 * <p>Using the default {@code ForkJoinPool.commonPool()} shares threads
 * with everything else in the JVM. Long-running LLM calls would starve
 * common-pool users. A named executor with a bounded pool gives us
 * back-pressure, thread-name visibility (jstack readable), and no
 * accidental interaction with other Spring subsystems.
 */
@Component
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final TriageRunRepository triageRuns;
    private final FindingRepository findings;
    private final AgentEventEmitter events;
    private final List<SpecialistAgent> specialists;
    private final long specialistTimeoutMs;
    private final ExecutorService specialistExecutor;

    public TriageOrchestrator(
            TriageRunRepository triageRuns,
            FindingRepository findings,
            AgentEventEmitter events,
            SymptomsSpecialist symptoms,
            ChangeSpecialist change,
            MetricsSpecialist metrics,
            PagerProperties properties) {
        this.triageRuns = triageRuns;
        this.findings = findings;
        this.events = events;
        this.specialists = List.of(symptoms, change, metrics);
        this.specialistTimeoutMs = properties.specialistTimeoutMs();
        // Fixed thread pool sized for our current specialist count.
        // When we add specialists dynamically (future PR), this becomes
        // a bounded pool with a queue and named "pager-specialist-N".
        this.specialistExecutor = Executors.newFixedThreadPool(
            specialists.size(),
            r -> {
                Thread t = new Thread(r);
                t.setName("pager-specialist-" + t.getId());
                t.setDaemon(false);
                return t;
            }
        );
    }

    /**
     * Run one triage: queued → running → 3 parallel specialists → completed.
     *
     * <p>NOT {@code @Transactional} — LLM calls take seconds; you can't
     * hold a DB transaction that long. State transitions use private
     * short-lived {@code @Transactional} helpers.
     */
    public void run(TriageJob job) {
        Optional<TriageRun> found = triageRuns.findById(job.triageId());
        if (found.isEmpty()) {
            log.warn("orchestrator asked to run triage {} but row not found — dropping",
                    job.triageId());
            return;
        }

        TriageRun triage = found.get();

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

                SpecialistInput input = buildInput(triage, rootSpan);

                // Kick off all three specialists in parallel. Each one
                // opens its own child span off rootSpan (that happens
                // inside AbstractLlmSpecialist.analyze). We only care
                // about coordination here.
                List<SpecialistOutput> outputs = runSpecialistsInParallel(input);

                // Persist all findings. When a specialist errored or
                // timed out, we still persist an UNKNOWN row so the
                // failure is queryable.
                for (int i = 0; i < specialists.size(); i++) {
                    persistFinding(triage, specialists.get(i).kind(), outputs.get(i));
                }

                // Pick the highest-confidence finding as the "primary"
                // summary for the triage. When the Aggregator lands in
                // PR #11, this will be replaced by a proper merge +
                // agreement-score computation. For now, argmax by
                // confidence is a reasonable placeholder.
                String primarySummary = outputs.stream()
                    .max((a, b) -> a.confidence().compareTo(b.confidence()))
                    .map(SpecialistOutput::summary)
                    .filter(s -> !s.isBlank())
                    .orElse("No specialist produced a usable finding.");

                markCompleted(triage, primarySummary);
                log.info("triage {} completed with {} findings",
                    triage.getId(), outputs.size());

                span.setOutcome("completed");
            } catch (RuntimeException e) {
                markFailed(triage);
                span.recordError(e);
                log.error("triage {} failed", triage.getId(), e);
                throw e;
            }
        }
    }

    // ---- parallel specialist orchestration ----

    /**
     * Runs all specialists concurrently and blocks until all complete or
     * their individual timeouts expire.
     *
     * <p>Never throws. A specialist that times out or crashes contributes
     * an UNKNOWN output to the returned list — same slot as it would have
     * had if it succeeded. Order matches {@code specialists} order.
     */
    private List<SpecialistOutput> runSpecialistsInParallel(SpecialistInput input) {
        List<CompletableFuture<SpecialistOutput>> futures = specialists.stream()
            .map(s -> CompletableFuture
                .supplyAsync(() -> s.analyze(input), specialistExecutor)
                .completeOnTimeout(
                    SpecialistOutput.unknown(
                        s.kind().name() + " specialist timed out after "
                            + specialistTimeoutMs + "ms"),
                    specialistTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(t -> SpecialistOutput.unknown(
                    s.kind().name() + " specialist crashed: " + t.getMessage()))
            )
            .collect(Collectors.toList());

        // allOf().join() waits for all futures. Each future has already
        // been mapped to either a real output, a timeout output, or an
        // exception output — so join won't throw here.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }

    // ---- transactional state transitions ----

    @Transactional
    protected void markRunning(TriageRun triage) {
        triage.statusEnum(TriageStatus.RUNNING);
        triage.setStartedAt(OffsetDateTime.now());
        triageRuns.save(triage);
    }

    @Transactional
    protected void persistFinding(TriageRun triage, Specialist kind, SpecialistOutput output) {
        Finding finding = new Finding();
        finding.setTriageId(triage.getId());
        finding.specialistEnum(kind);
        finding.categoryEnum(output.category());
        finding.severityEnum(triage.severityEnum() != null
            ? triage.severityEnum()
            : dev.sumituppal.pager.domain.Severity.P4);
        finding.setSummary(output.summary());
        finding.setConfidence(output.confidence());
        finding.setRationale(output.payload());
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

    // ---- helpers ----

    private SpecialistInput buildInput(TriageRun triage, SpanContext rootSpan) {
        return new SpecialistInput(
            triage.getId(),
            triage.getIncidentId(),
            triage.getAlertSummary(),
            triage.getService(),
            triage.severityEnum() != null ? triage.severityEnum().name() : "UNKNOWN",
            rootSpan
        );
    }
}