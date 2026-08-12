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
import dev.sumituppal.pager.specialist.Aggregator;
import dev.sumituppal.pager.specialist.Aggregator.SpecialistFinding;
import dev.sumituppal.pager.specialist.ChangeSpecialist;
import dev.sumituppal.pager.specialist.CommsSpecialist;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The orchestrator: takes a job off the queue and drives one triage from
 * queued → running → 4-way specialist fan-out → aggregation → completed.
 *
 * <h2>The full pipeline (PR #11)</h2>
 * <ol>
 *   <li>Load the triage row, verify not terminal.</li>
 *   <li>Mark it running.</li>
 *   <li>Fan out to 4 specialists in parallel (Symptoms, Change,
 *       Metrics, Comms) via {@link CompletableFuture}.</li>
 *   <li>Persist all 4 findings.</li>
 *   <li>Run the Aggregator over the 4 findings.</li>
 *   <li>Persist the aggregator's merged finding (5th row, with a real
 *       cause category).</li>
 *   <li>Mark the triage completed with the merged summary.</li>
 * </ol>
 *
 * <h2>Why persist the aggregator finding as a 5th row?</h2>
 * <p>The {@code findings} table becomes self-describing: a single
 * {@code SELECT * FROM findings WHERE triage_id = ?} returns all 4
 * specialist views plus the merged conclusion. The trace viewer,
 * cost ledger, and audit workflows all read from the same table
 * without re-joining triage_runs.
 *
 * <h2>Failure isolation</h2>
 * <p>Each specialist can fail independently; the aggregator can fail
 * independently; the triage still completes. If all four specialists
 * timed out, the aggregator falls back to whatever the highest-
 * confidence input was, and the triage completes with that.
 */
@Component
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final TriageRunRepository triageRuns;
    private final FindingRepository findings;
    private final AgentEventEmitter events;
    private final List<SpecialistAgent> specialists;
    private final Aggregator aggregator;
    private final long specialistTimeoutMs;
    private final ExecutorService specialistExecutor;

    public TriageOrchestrator(
            TriageRunRepository triageRuns,
            FindingRepository findings,
            AgentEventEmitter events,
            SymptomsSpecialist symptoms,
            ChangeSpecialist change,
            MetricsSpecialist metrics,
            CommsSpecialist comms,
            Aggregator aggregator,
            PagerProperties properties) {
        this.triageRuns = triageRuns;
        this.findings = findings;
        this.events = events;
        this.specialists = List.of(symptoms, change, metrics, comms);
        this.aggregator = aggregator;
        this.specialistTimeoutMs = properties.specialistTimeoutMs();
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
                triage.getId(), Specialist.AGGREGATOR, "orchestrator");

        try (Span span = Span.open(events, rootSpan)) {
            try {
                markRunning(triage);
                log.info("triage {} started", triage.getId());

                SpecialistInput input = buildInput(triage, rootSpan);

                // Step 1 — run all specialists in parallel.
                List<SpecialistOutput> specialistOutputs = runSpecialistsInParallel(input);

                // Step 2 — persist each specialist finding.
                for (int i = 0; i < specialists.size(); i++) {
                    persistFinding(triage, specialists.get(i).kind(),
                        specialistOutputs.get(i));
                }
                log.info("triage {} persisted {} specialist findings",
                    triage.getId(), specialistOutputs.size());

                // Step 3 — run the aggregator over the specialist findings.
                List<SpecialistFinding> aggregatorInputs = new ArrayList<>();
                for (int i = 0; i < specialists.size(); i++) {
                    SpecialistOutput out = specialistOutputs.get(i);
                    aggregatorInputs.add(new SpecialistFinding(
                        specialists.get(i).kind().name().toLowerCase(),
                        out.summary(),
                        out.confidence(),
                        extractReasoning(out.payload())
                    ));
                }
                SpecialistOutput merged = aggregator.aggregate(
                    triage.getId(), rootSpan, aggregatorInputs);

                // Step 4 — persist the aggregator's merged finding as a 5th row.
                persistFinding(triage, Specialist.AGGREGATOR, merged);

                // Step 5 — mark the triage completed with the merged summary.
                String finalSummary = !merged.summary().isBlank()
                    ? merged.summary()
                    : "No specialist produced a usable finding.";
                markCompleted(triage, finalSummary);
                log.info("triage {} completed — category={}, confidence={}, summary=\"{}\"",
                    triage.getId(),
                    merged.category().dbValue(),
                    merged.confidence(),
                    truncate(finalSummary, 80));

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

    /**
     * Extract the reasoning field from a specialist's payload JSON, if
     * present. Aggregator's prompt is more useful when it can see WHY
     * each specialist reached its conclusion, not just what they said.
     */
    private String extractReasoning(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return "";
        // Cheap substring extraction — proper Jackson parse would be
        // overkill for one field. If the payload isn't a well-formed
        // JSON object with a "reasoning" key, we get "".
        int idx = payloadJson.indexOf("\"reasoning\"");
        if (idx < 0) return "";
        int colonIdx = payloadJson.indexOf(':', idx);
        if (colonIdx < 0) return "";
        int quoteStart = payloadJson.indexOf('"', colonIdx);
        if (quoteStart < 0) return "";
        int quoteEnd = payloadJson.indexOf('"', quoteStart + 1);
        // Handle escaped quotes minimally.
        while (quoteEnd > 0 && payloadJson.charAt(quoteEnd - 1) == '\\') {
            quoteEnd = payloadJson.indexOf('"', quoteEnd + 1);
        }
        if (quoteEnd < 0) return "";
        return payloadJson.substring(quoteStart + 1, quoteEnd);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}