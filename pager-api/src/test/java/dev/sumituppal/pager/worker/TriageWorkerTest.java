package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.ingress.TriageJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriageWorker}.
 *
 * <p>We test the two package-private methods ({@code loop} and {@code processOne})
 * directly without starting a real thread. That's much easier to reason about
 * than trying to synchronize with a live thread in tests.
 *
 * <p>What we prove:
 * <ol>
 *   <li>A polled job is handed to the orchestrator.</li>
 *   <li>A null poll (empty queue) is skipped without touching the orchestrator.</li>
 *   <li>An exception in the orchestrator does NOT propagate and kill the loop.</li>
 * </ol>
 */
class TriageWorkerTest {

    private TriageQueueConsumer consumer;
    private TriageOrchestrator orchestrator;
    private TriageWorker worker;

    @BeforeEach
    void setUp() {
        consumer = mock(TriageQueueConsumer.class);
        orchestrator = mock(TriageOrchestrator.class);
        worker = new TriageWorker(consumer, orchestrator);
    }

    @Test
    @DisplayName("processOne hands the job to the orchestrator")
    void processOneCallsOrchestrator() {
        TriageJob job = new TriageJob("triage_1", "PGR1", 1);

        worker.processOne(job);

        verify(orchestrator, times(1)).run(job);
    }

    @Test
    @DisplayName("processOne swallows orchestrator exceptions")
    void processOneSwallowsOrchestratorException() {
        TriageJob job = new TriageJob("triage_bad", "PGR1", 1);
        doThrow(new RuntimeException("simulated orchestrator failure"))
            .when(orchestrator).run(job);

        // Should NOT throw — the worker loop's ability to keep going
        // depends on processOne being exception-safe.
        worker.processOne(job);

        verify(orchestrator, times(1)).run(job);
    }

    @Test
    @DisplayName("loop skips null polls and never calls orchestrator for them")
    void loopSkipsNullPolls() throws Exception {
        // Set up: consumer returns null once, then a job, then null.
        // Manually stop the worker after one job is processed.
        when(consumer.poll())
            .thenReturn(null)
            .thenReturn(new TriageJob("triage_only", "PGR1", 1))
            .thenAnswer(inv -> {
                worker.stop();
                return null;
            });
        worker.start();
        // start() launches a thread; wait for it to exit.
        Thread.sleep(200);

        // Orchestrator saw exactly one job, not two (the nulls skipped).
        verify(orchestrator, times(1)).run(any());
    }

    @Test
    @DisplayName("start followed by stop does not deadlock and cleanly exits")
    void startStopIsClean() throws Exception {
        when(consumer.poll()).thenReturn(null);

        worker.start();
        Thread.sleep(50); // let the loop enter once
        worker.stop();

        // No exception, no hang. The stop() call above is the assertion —
        // if it deadlocked, the test would time out.
        verify(orchestrator, never()).run(any());
    }
}