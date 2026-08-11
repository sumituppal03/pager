package dev.sumituppal.pager.worker;

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
import dev.sumituppal.pager.specialist.SpecialistInput;
import dev.sumituppal.pager.specialist.SpecialistOutput;
import dev.sumituppal.pager.specialist.SymptomsSpecialist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>These tests prove the state-machine transitions, the specialist
 * integration, and that the orchestrator emits observability signals
 * correctly. All external dependencies (repos, specialist, emitter)
 * are mocked.
 */
class TriageOrchestratorTest {

    private TriageRunRepository triageRuns;
    private FindingRepository findings;
    private AgentEventEmitter events;
    private SymptomsSpecialist symptoms;
    private TriageOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        findings = mock(FindingRepository.class);
        events = mock(AgentEventEmitter.class);
        symptoms = mock(SymptomsSpecialist.class);
        orchestrator = new TriageOrchestrator(triageRuns, findings, events, symptoms);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path — specialist runs, finding persists, triage completes
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path runs specialist, persists finding, transitions to completed")
    void happyPathCompletesTriage() {
        TriageRun triage = newQueuedTriage("triage_123");
        when(triageRuns.findById("triage_123")).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("Checkout 5xx spike detected", "0.85"));

        orchestrator.run(new TriageJob("triage_123", "PGR1", 1));

        // Specialist was invoked with the triage details.
        ArgumentCaptor<SpecialistInput> specialistInput = ArgumentCaptor.forClass(SpecialistInput.class);
        verify(symptoms, times(1)).analyze(specialistInput.capture());
        assertThat(specialistInput.getValue().triageId()).isEqualTo("triage_123");
        assertThat(specialistInput.getValue().alertSummary()).isEqualTo("test alert");
        assertThat(specialistInput.getValue().parentSpan()).isNotNull();

        // Finding was persisted.
        // Finding was persisted.
        ArgumentCaptor<Finding> saved = ArgumentCaptor.forClass(Finding.class);
        verify(findings, times(1)).save(saved.capture());
        Finding f = saved.getValue();
        assertThat(f.getTriageId()).isEqualTo("triage_123");
        assertThat(f.specialistEnum()).isEqualTo(Specialist.SYMPTOMS);
        assertThat(f.severityEnum()).isEqualTo(Severity.P2); // inherited from triage
        assertThat(f.getSummary()).isEqualTo("Checkout 5xx spike detected");
        assertThat(f.getConfidence()).isEqualByComparingTo("0.85");
        assertThat(f.getRationale()).isNotBlank(); // payload from specialist

        // Triage was marked completed with the specialist's summary.
        ArgumentCaptor<TriageRun> triageSaves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(triageSaves.capture());
        TriageRun finalState = triageSaves.getAllValues().get(1);
        assertThat(finalState.statusEnum()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(finalState.getAggregatedSummary()).isEqualTo("Checkout 5xx spike detected");
    }

    @Test
    @DisplayName("happy path emits span.start and span.end")
    void happyPathEmitsSpans() {
        TriageRun triage = newQueuedTriage("triage_span");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("summary", "0.5"));

        orchestrator.run(new TriageJob("triage_span", "PGR1", 1));

        verify(events, times(1)).spanStart(any());
        verify(events, times(1)).spanEnd(any(), anyLong(), eq("completed"));
        verify(events, never()).error(any(), anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency — terminal-state and missing triages
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing triage row is dropped without side effects")
    void missingTriageIsDropped() {
        when(triageRuns.findById(any())).thenReturn(Optional.empty());

        orchestrator.run(new TriageJob("triage_missing", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(findings, never()).save(any());
        verify(symptoms, never()).analyze(any());
        verify(events, never()).spanStart(any());
    }

    @Test
    @DisplayName("already-completed triage is skipped")
    void alreadyCompletedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_done");
        triage.statusEnum(TriageStatus.COMPLETED);
        when(triageRuns.findById("triage_done")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_done", "PGR1", 1));

        verify(symptoms, never()).analyze(any());
        verify(triageRuns, never()).save(any());
    }

    @Test
    @DisplayName("already-failed triage is skipped")
    void alreadyFailedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_dead");
        triage.statusEnum(TriageStatus.FAILED);
        when(triageRuns.findById("triage_dead")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_dead", "PGR1", 1));

        verify(symptoms, never()).analyze(any());
    }

    // ─────────────────────────────────────────────────────────────
    // Failure paths
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("specialist returning UNKNOWN finding is persisted, triage still completes")
    void specialistUnknownFindingCompletes() {
        TriageRun triage = newQueuedTriage("triage_unknown");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any(SpecialistInput.class)))
            .thenReturn(SpecialistOutput.unknown("LLM returned garbage"));

        orchestrator.run(new TriageJob("triage_unknown", "PGR1", 1));

        // Finding was persisted with UNKNOWN category.
        ArgumentCaptor<Finding> saved = ArgumentCaptor.forClass(Finding.class);
        verify(findings, times(1)).save(saved.capture());
        assertThat(saved.getValue().categoryEnum()).isEqualTo(FindingCategory.UNKNOWN);
        assertThat(saved.getValue().getConfidence()).isEqualByComparingTo("0.0");

        // Triage was still completed (specialist errors are recorded, not fatal).
        verify(triageRuns, times(2)).save(any());
    }

    @Test
    @DisplayName("finding save failure marks triage FAILED and emits error span")
    void findingSaveFailureFailsTriage() {
        TriageRun triage = newQueuedTriage("triage_dbfail");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));
        when(symptoms.analyze(any(SpecialistInput.class)))
            .thenReturn(sampleFinding("summary", "0.5"));

        // Fail the finding save. The mark-failed path saves the triage
        // twice: once for RUNNING, once for FAILED. Use a call counter
        // to simulate the save on the second call (COMPLETED transition)
        // failing.
        AtomicInteger findingCallCount = new AtomicInteger(0);
        when(findings.save(any())).thenAnswer(inv -> {
            if (findingCallCount.incrementAndGet() == 1) {
                throw new RuntimeException("simulated DB failure");
            }
            return inv.getArgument(0);
        });

        try {
            orchestrator.run(new TriageJob("triage_dbfail", "PGR1", 1));
        } catch (RuntimeException expected) {
            // Re-throw is intentional so upstream (transaction, worker)
            // sees the failure.
        }

        verify(events, times(1)).error(any(), anyString());
        verify(events, times(1)).spanEnd(any(), anyLong(), eq("error"));
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