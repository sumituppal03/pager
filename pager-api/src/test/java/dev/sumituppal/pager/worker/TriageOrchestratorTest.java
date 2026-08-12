package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.domain.Finding;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.FindingRepository;
import dev.sumituppal.pager.domain.Severity;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.specialist.Aggregator;
import dev.sumituppal.pager.specialist.ChangeSpecialist;
import dev.sumituppal.pager.specialist.CommsSpecialist;
import dev.sumituppal.pager.specialist.MetricsSpecialist;
import dev.sumituppal.pager.specialist.SpecialistInput;
import dev.sumituppal.pager.specialist.SpecialistOutput;
import dev.sumituppal.pager.specialist.SymptomsSpecialist;
import static org.mockito.ArgumentMatchers.anyList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import dev.sumituppal.pager.domain.NotificationDecision;
import dev.sumituppal.pager.hitl.HitlDecisionResult;
import dev.sumituppal.pager.hitl.HitlGate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriageOrchestrator} — full 4-specialist + Aggregator pipeline.
 */
class TriageOrchestratorTest {

    private TriageRunRepository triageRuns;
    private FindingRepository findings;
    private AgentEventEmitter events;
    private SymptomsSpecialist symptoms;
    private ChangeSpecialist change;
    private MetricsSpecialist metrics;
    private CommsSpecialist comms;
    private Aggregator aggregator;
    private HitlGate hitlGate;
    private TriageOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        findings = mock(FindingRepository.class);
        events = mock(AgentEventEmitter.class);
        symptoms = mock(SymptomsSpecialist.class);
        change = mock(ChangeSpecialist.class);
        metrics = mock(MetricsSpecialist.class);
        comms = mock(CommsSpecialist.class);
        hitlGate = mock(HitlGate.class);
        when(hitlGate.gate(anyString(), any(), any(), anyString()))
            .thenReturn(new HitlDecisionResult(
                NotificationDecision.AUTO_POSTED,
                "test message", "log", "test reason"));
        aggregator = mock(Aggregator.class);

        when(symptoms.kind()).thenReturn(Specialist.SYMPTOMS);
        when(change.kind()).thenReturn(Specialist.CHANGE);
        when(metrics.kind()).thenReturn(Specialist.METRICS);
        when(comms.kind()).thenReturn(Specialist.COMMS);

        PagerProperties properties = new PagerProperties(
            new BigDecimal("0.75"),
            45000L, 15000L,
            new PagerProperties.Models("llama-3.3-70b-versatile",
                                       "llama-3.3-70b-versatile",
                                       "text-embedding-3-small"),
            new BigDecimal("20.00"),
            "pager.triage.queue", "test-secret");

        orchestrator = new TriageOrchestrator(
            triageRuns, findings, events,
            symptoms, change, metrics, comms,
            aggregator, hitlGate, properties);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path — 4 specialists + aggregator = 5 findings total
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full pipeline runs 4 specialists + aggregator, persists 5 findings")
    void fullPipelinePersistsFiveFindings() {
        TriageRun triage = newQueuedTriage("triage_123");
        when(triageRuns.findById("triage_123")).thenReturn(Optional.of(triage));

        when(symptoms.analyze(any())).thenReturn(sample("symptoms summary", "0.85"));
        when(change.analyze(any())).thenReturn(sample("change summary", "0.40"));
        when(metrics.analyze(any())).thenReturn(sample("metrics summary", "0.30"));
        when(comms.analyze(any())).thenReturn(sample("comms draft", "0.70"));

        SpecialistOutput mergedOutput = new SpecialistOutput(
            FindingCategory.DEPLOY_REGRESSION,
            "Merged: Checkout 5xx likely from recent deploy",
            new BigDecimal("0.82"),
            "{\"reasoning\":\"merged\"}"
        );
        when(aggregator.aggregate(anyString(), any(), anyList())).thenReturn(mergedOutput);

        orchestrator.run(new TriageJob("triage_123", "PGR1", 1));

        // 5 findings: 4 specialists + 1 aggregator
        ArgumentCaptor<Finding> saved = ArgumentCaptor.forClass(Finding.class);
        verify(findings, times(5)).save(saved.capture());
        List<Finding> allSaved = saved.getAllValues();

        assertThat(allSaved).extracting(Finding::specialistEnum)
            .containsExactlyInAnyOrder(
                Specialist.SYMPTOMS, Specialist.CHANGE,
                Specialist.METRICS, Specialist.COMMS,
                Specialist.AGGREGATOR);

        // The aggregator finding has the real category
        Finding aggregatorFinding = allSaved.stream()
            .filter(f -> f.specialistEnum() == Specialist.AGGREGATOR)
            .findFirst()
            .orElseThrow();
        assertThat(aggregatorFinding.categoryEnum()).isEqualTo(FindingCategory.DEPLOY_REGRESSION);
        assertThat(aggregatorFinding.getSummary()).contains("Merged");

        // Triage completed with the merged summary
        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        TriageRun finalState = triageSaves.getAllValues().get(1);
        assertThat(finalState.statusEnum()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(finalState.getAggregatedSummary())
            .isEqualTo("Merged: Checkout 5xx likely from recent deploy");
    }

    @Test
    @DisplayName("aggregator runs after all 4 specialists complete")
    void aggregatorRunsAfterSpecialists() {
        TriageRun triage = newQueuedTriage("triage_order");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));

        when(symptoms.analyze(any())).thenReturn(sample("s", "0.5"));
        when(change.analyze(any())).thenReturn(sample("c", "0.4"));
        when(metrics.analyze(any())).thenReturn(sample("m", "0.3"));
        when(comms.analyze(any())).thenReturn(sample("co", "0.6"));

        when(aggregator.aggregate(anyString(), any(), anyList()))
            .thenReturn(sample("merged", "0.7"));

        orchestrator.run(new TriageJob("triage_order", "PGR1", 1));

        // Aggregator called exactly once with all 4 findings
        ArgumentCaptor<List<Aggregator.SpecialistFinding>> aggInputs =
            ArgumentCaptor.forClass(List.class);
        verify(aggregator, times(1)).aggregate(
            eq("triage_order"), any(), aggInputs.capture());
        assertThat(aggInputs.getValue()).hasSize(4);
    }

    // ─────────────────────────────────────────────────────────────
    // Failure isolation
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("one specialist crashing does not kill the pipeline")
    void oneCrashDoesNotKillPipeline() {
        TriageRun triage = newQueuedTriage("triage_crash");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));

        when(symptoms.analyze(any())).thenReturn(sample("s", "0.7"));
        when(change.analyze(any())).thenThrow(new RuntimeException("boom"));
        when(metrics.analyze(any())).thenReturn(sample("m", "0.5"));
        when(comms.analyze(any())).thenReturn(sample("c", "0.6"));

        when(aggregator.aggregate(anyString(), any(), anyList()))
            .thenReturn(sample("merged", "0.6"));

        orchestrator.run(new TriageJob("triage_crash", "PGR1", 1));

        // Still 5 findings persisted (crashed one becomes UNKNOWN)
        verify(findings, times(5)).save(any());

        // Triage still completed
        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        assertThat(triageSaves.getAllValues().get(1).statusEnum())
            .isEqualTo(TriageStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing triage row drops without side effects")
    void missingTriageIsDropped() {
        when(triageRuns.findById(any())).thenReturn(Optional.empty());

        orchestrator.run(new TriageJob("triage_missing", "PGR1", 1));

        verify(symptoms, never()).analyze(any());
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(findings, never()).save(any());
    }

    @Test
    @DisplayName("already-completed triage is skipped")
    void alreadyCompletedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_done");
        triage.statusEnum(TriageStatus.COMPLETED);
        when(triageRuns.findById("triage_done")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_done", "PGR1", 1));

        verify(symptoms, never()).analyze(any());
        verify(aggregator, never()).aggregate(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────
    // Observability
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path emits span.start and span.end on the orchestrator")
    void happyPathEmitsRootSpan() {
        TriageRun triage = newQueuedTriage("triage_span");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any())).thenReturn(sample("s", "0.5"));
        when(change.analyze(any())).thenReturn(sample("c", "0.4"));
        when(metrics.analyze(any())).thenReturn(sample("m", "0.3"));
        when(comms.analyze(any())).thenReturn(sample("co", "0.6"));
        when(aggregator.aggregate(anyString(), any(), anyList()))
            .thenReturn(sample("merged", "0.7"));

        orchestrator.run(new TriageJob("triage_span", "PGR1", 1));

        verify(events, times(1)).spanStart(any());
        verify(events, times(1)).spanEnd(any(), anyLong(), eq("completed"));
        verify(events, never()).error(any(), anyString());
    }

    @Test
    @DisplayName("gate is called with the aggregator's merged output")
    void gateIsCalled() {
        TriageRun triage = newQueuedTriage("triage_gate");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any())).thenReturn(sample("s", "0.5"));
        when(change.analyze(any())).thenReturn(sample("c", "0.4"));
        when(metrics.analyze(any())).thenReturn(sample("m", "0.3"));
        when(comms.analyze(any())).thenReturn(sample("co", "0.6"));

        SpecialistOutput merged = new SpecialistOutput(
            FindingCategory.DEPLOY_REGRESSION,
            "merged summary",
            new BigDecimal("0.80"),
            "{\"reasoning\":\"merged\"}"
        );
        when(aggregator.aggregate(anyString(), any(), anyList())).thenReturn(merged);

        orchestrator.run(new TriageJob("triage_gate", "PGR1", 1));

        verify(hitlGate, times(1)).gate(
            eq("triage_gate"),
            eq(FindingCategory.DEPLOY_REGRESSION),
            eq(new BigDecimal("0.80")),
            eq("merged summary"));
    }

    // ----- helpers -----

    private static TriageRun newQueuedTriage(String id) {
        TriageRun t = new TriageRun();
        t.setId(id);
        t.setIdempotencyKey("idem-" + id);
        t.setIncidentId("PGR1");
        t.setAlertSummary("test alert");
        t.severityEnum(Severity.P2);
        t.statusEnum(TriageStatus.QUEUED);
        t.setRawPayload("{}");
        return t;
    }

    private static SpecialistOutput sample(String summary, String confidence) {
        return new SpecialistOutput(
            FindingCategory.UNKNOWN,
            summary,
            new BigDecimal(confidence),
            "{\"reasoning\":\"stub reasoning\"}"
        );
    }
}