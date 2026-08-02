package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.domain.Severity;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriageOrchestrator}.
 *
 * <p>The tests here prove the state-machine transitions and idempotency
 * — the aspects of the orchestrator that will remain stable even after
 * the specialist fan-out gets added in later PRs. The stub summary text
 * is intentionally NOT asserted (it will change).
 */
class TriageOrchestratorTest {

    private TriageRunRepository triageRuns;
    private TriageOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        orchestrator = new TriageOrchestrator(triageRuns);
    }

    @Test
    @DisplayName("queued triage transitions running → completed")
    void queuedTriageCompletes() {
        TriageRun triage = newQueuedTriage("triage_123");
        when(triageRuns.findById("triage_123")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_123", "PGR1", 1));

        // Two saves: one to mark running, one to mark completed.
        ArgumentCaptor<TriageRun> saves = ArgumentCaptor.forClass(TriageRun.class);
        verify(triageRuns, times(2)).save(saves.capture());

        TriageRun finalState = saves.getAllValues().get(1);
        assertThat(finalState.statusEnum()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(finalState.getStartedAt()).isNotNull();
        assertThat(finalState.getCompletedAt()).isNotNull();
        assertThat(finalState.getAggregatedSummary()).isNotBlank();
    }

    @Test
    @DisplayName("missing triage row is logged and dropped, no exception")
    void missingTriageIsDropped() {
        when(triageRuns.findById(any())).thenReturn(Optional.empty());

        // Should not throw
        orchestrator.run(new TriageJob("triage_missing", "PGR1", 1));

        // Nothing to save
        verify(triageRuns, times(0)).save(any());
    }

    @Test
    @DisplayName("already-completed triage is skipped (idempotent)")
    void alreadyCompletedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_done");
        triage.statusEnum(TriageStatus.COMPLETED);
        when(triageRuns.findById("triage_done")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_done", "PGR1", 1));

        verify(triageRuns, times(0)).save(any());
    }

    @Test
    @DisplayName("already-failed triage is skipped (idempotent)")
    void alreadyFailedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_dead");
        triage.statusEnum(TriageStatus.FAILED);
        when(triageRuns.findById("triage_dead")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_dead", "PGR1", 1));

        verify(triageRuns, times(0)).save(any());
    }

    @Test
    @DisplayName("already-cancelled triage is skipped (idempotent)")
    void alreadyCancelledIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_x");
        triage.statusEnum(TriageStatus.CANCELLED);
        when(triageRuns.findById("triage_x")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_x", "PGR1", 1));

        verify(triageRuns, times(0)).save(any());
    }

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
}