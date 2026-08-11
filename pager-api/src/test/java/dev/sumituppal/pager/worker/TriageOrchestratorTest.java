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
import dev.sumituppal.pager.specialist.ChangeSpecialist;
import dev.sumituppal.pager.specialist.MetricsSpecialist;
import dev.sumituppal.pager.specialist.SpecialistInput;
import dev.sumituppal.pager.specialist.SpecialistOutput;
import dev.sumituppal.pager.specialist.SymptomsSpecialist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriageOrchestrator}.
 *
 * <p>Covers the parallel-fan-out behavior: three specialists run,
 * three findings persist, one primary summary picked by argmax
 * confidence.
 */
class TriageOrchestratorTest {

    private TriageRunRepository triageRuns;
    private FindingRepository findings;
    private AgentEventEmitter events;
    private SymptomsSpecialist symptoms;
    private ChangeSpecialist change;
    private MetricsSpecialist metrics;
    private TriageOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        findings = mock(FindingRepository.class);
        events = mock(AgentEventEmitter.class);
        symptoms = mock(SymptomsSpecialist.class);
        change = mock(ChangeSpecialist.class);
        metrics = mock(MetricsSpecialist.class);

        when(symptoms.kind()).thenReturn(Specialist.SYMPTOMS);
        when(change.kind()).thenReturn(Specialist.CHANGE);
        when(metrics.kind()).thenReturn(Specialist.METRICS);

        PagerProperties properties = new PagerProperties(
            new BigDecimal("0.75"),
            45000L,
            15000L,
            new PagerProperties.Models("llama-3.3-70b-versatile",
                                       "llama-3.3-70b-versatile",
                                       "text-embedding-3-small"),
            new BigDecimal("20.00"),
            "pager.triage.queue",
            "test-secret");

        orchestrator = new TriageOrchestrator(
            triageRuns, findings, events,
            symptoms, change, metrics,
            properties);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path — three specialists run, three findings persist
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all three specialists run and persist findings")
    void allThreeSpecialistsPersist() {
        TriageRun triage = newQueuedTriage("triage_123");
        when(triageRuns.findById("triage_123")).thenReturn(Optional.of(triage));

        when(symptoms.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("symptoms summary", "0.85"));
        when(change.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("change summary", "0.40"));
        when(metrics.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("metrics summary", "0.30"));

        orchestrator.run(new TriageJob("triage_123", "PGR1", 1));

        // All three specialists were called.
        verify(symptoms, times(1)).analyze(any());
        verify(change, times(1)).analyze(any());
        verify(metrics, times(1)).analyze(any());

        // Three findings persisted.
        ArgumentCaptor<Finding> saved = ArgumentCaptor.forClass(Finding.class);
        verify(findings, times(3)).save(saved.capture());
        List<Finding> allSaved = saved.getAllValues();

        assertThat(allSaved).extracting(Finding::specialistEnum)
            .containsExactlyInAnyOrder(
                Specialist.SYMPTOMS, Specialist.CHANGE, Specialist.METRICS);

        // Primary summary in triage_runs uses the highest-confidence finding.
        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        TriageRun finalState = triageSaves.getAllValues().get(1);
        assertThat(finalState.statusEnum()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(finalState.getAggregatedSummary()).isEqualTo("symptoms summary");
    }

    // ─────────────────────────────────────────────────────────────
    // Failure isolation — one specialist failing doesn't kill others
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("one specialist crashing does not affect the others")
    void oneCrashDoesNotKillOthers() {
        TriageRun triage = newQueuedTriage("triage_crash");
        when(triageRuns.findById("triage_crash")).thenReturn(Optional.of(triage));

        when(symptoms.analyze(any())).thenReturn(sampleFinding("s", "0.7"));
        when(change.analyze(any()))
            .thenThrow(new RuntimeException("change specialist blew up"));
        when(metrics.analyze(any())).thenReturn(sampleFinding("m", "0.5"));

        orchestrator.run(new TriageJob("triage_crash", "PGR1", 1));

        // All three findings should still be persisted (the crashed
        // one becomes UNKNOWN).
        verify(findings, times(3)).save(any());

        // Triage still completed.
        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        assertThat(triageSaves.getAllValues().get(1).statusEnum())
            .isEqualTo(TriageStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing triage row is dropped without touching specialists")
    void missingTriageIsDropped() {
        when(triageRuns.findById(any())).thenReturn(Optional.empty());

        orchestrator.run(new TriageJob("triage_missing", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(findings, never()).save(any());
        verify(symptoms, never()).analyze(any());
        verify(change, never()).analyze(any());
        verify(metrics, never()).analyze(any());
    }

    @Test
    @DisplayName("already-completed triage is skipped")
    void alreadyCompletedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_done");
        triage.statusEnum(TriageStatus.COMPLETED);
        when(triageRuns.findById("triage_done")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_done", "PGR1", 1));

        verify(symptoms, never()).analyze(any());
        verify(change, never()).analyze(any());
        verify(metrics, never()).analyze(any());
    }

    // ─────────────────────────────────────────────────────────────
    // Observability
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path emits span.start and span.end on the orchestrator")
    void happyPathEmitsRootSpan() {
        TriageRun triage = newQueuedTriage("triage_span");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any())).thenReturn(sampleFinding("x", "0.5"));
        when(change.analyze(any())).thenReturn(sampleFinding("y", "0.4"));
        when(metrics.analyze(any())).thenReturn(sampleFinding("z", "0.3"));

        orchestrator.run(new TriageJob("triage_span", "PGR1", 1));

        verify(events, times(1)).spanStart(any());
        verify(events, times(1)).spanEnd(any(), anyLong(), eq("completed"));
        verify(events, never()).error(any(), anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // Fallback summary — all specialists returned blank
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all specialists returning blank summaries yields fallback text")
    void allBlankSummariesUsesFallback() {
        TriageRun triage = newQueuedTriage("triage_blank");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any())).thenReturn(sampleFinding("", "0.0"));
        when(change.analyze(any())).thenReturn(sampleFinding("", "0.0"));
        when(metrics.analyze(any())).thenReturn(sampleFinding("", "0.0"));

        orchestrator.run(new TriageJob("triage_blank", "PGR1", 1));

        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        assertThat(triageSaves.getAllValues().get(1).getAggregatedSummary())
            .isEqualTo("No specialist produced a usable finding.");
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

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

    private static SpecialistOutput sampleFinding(String summary, String confidence) {
        return new SpecialistOutput(
            FindingCategory.UNKNOWN,
            summary,
            new BigDecimal(confidence),
            "{}"
        );
    }
}