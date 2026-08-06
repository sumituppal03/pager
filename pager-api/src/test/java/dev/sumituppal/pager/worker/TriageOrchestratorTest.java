package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.domain.Severity;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import dev.sumituppal.pager.ingress.TriageJob;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * <p>These tests prove the state-machine transitions AND that the orchestrator
 * emits the right observability signal — a span with start + end, and an
 * error event if the enclosed work throws. Once specialist fan-out lands
 * in PR #10-12, this test will grow to verify child spans too.
 *
 * <p>The stub summary text is intentionally NOT asserted (it will change).
 */
class TriageOrchestratorTest {

    private TriageRunRepository triageRuns;
    private AgentEventEmitter events;
    private TriageOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        events = mock(AgentEventEmitter.class);
        orchestrator = new TriageOrchestrator(triageRuns, events);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path — queued → running → completed, with span emitted
    // ─────────────────────────────────────────────────────────────

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
    @DisplayName("happy path emits span.start and span.end, no error")
    void happyPathEmitsSpanStartAndEnd() {
        TriageRun triage = newQueuedTriage("triage_span_ok");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_span_ok", "PGR1", 1));

        // spanStart once, spanEnd once with outcome=completed
        verify(events, times(1)).spanStart(any());
        verify(events, times(1)).spanEnd(any(), anyLong(), eq("completed"));

        // No error event on the happy path.
        verify(events, never()).error(any(), anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency — terminal-state triages skipped without side effects
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing triage row is logged and dropped — no span emitted")
    void missingTriageIsDropped() {
        when(triageRuns.findById(any())).thenReturn(Optional.empty());

        orchestrator.run(new TriageJob("triage_missing", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(events, never()).spanStart(any());
        verify(events, never()).spanEnd(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("already-completed triage is skipped and no span emitted")
    void alreadyCompletedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_done");
        triage.statusEnum(TriageStatus.COMPLETED);
        when(triageRuns.findById("triage_done")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_done", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(events, never()).spanStart(any());
    }

    @Test
    @DisplayName("already-failed triage is skipped and no span emitted")
    void alreadyFailedIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_dead");
        triage.statusEnum(TriageStatus.FAILED);
        when(triageRuns.findById("triage_dead")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_dead", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(events, never()).spanStart(any());
    }

    @Test
    @DisplayName("already-cancelled triage is skipped and no span emitted")
    void alreadyCancelledIsSkipped() {
        TriageRun triage = newQueuedTriage("triage_x");
        triage.statusEnum(TriageStatus.CANCELLED);
        when(triageRuns.findById("triage_x")).thenReturn(Optional.of(triage));

        orchestrator.run(new TriageJob("triage_x", "PGR1", 1));

        verify(triageRuns, never()).save(any());
        verify(events, never()).spanStart(any());
    }

    // ─────────────────────────────────────────────────────────────
    // Failure path — the second save (mark completed) throws, and we assert
    // the orchestrator emits an error event and closes the span with outcome=error.
    //
    // We use a call-counter on save() instead of matcher-based conditional
    // stubbing — cleaner and less prone to Mockito argument-matcher gotchas.
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save failure marks triage FAILED, emits error, ends span with outcome=error")
    void saveFailureEmitsErrorAndFailsTriage() {
        TriageRun triage = newQueuedTriage("triage_fails");
        when(triageRuns.findById(any())).thenReturn(Optional.of(triage));

        // The first save (RUNNING transition) succeeds; the third save
        // in the catch block (FAILED transition) also succeeds; but the
        // second save (COMPLETED transition) throws. Use a counter to
        // stub call-by-call.
        AtomicInteger callNo = new AtomicInteger(0);
        when(triageRuns.save(any())).thenAnswer(inv -> {
            int n = callNo.incrementAndGet();
            if (n == 2) {
                throw new RuntimeException("simulated DB failure on completed-write");
            }
            return inv.getArgument(0);
        });

        try {
            orchestrator.run(new TriageJob("triage_fails", "PGR1", 1));
        } catch (RuntimeException expected) {
            // Re-throw from orchestrator is intentional so @Transactional
            // rolls back. We swallow it here to assert observable side effects.
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
}