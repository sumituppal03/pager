package dev.sumituppal.pager.worker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import dev.sumituppal.pager.ingress.TriageJob;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The background thread that drains the Redis queue and hands each job
 * to the {@link TriageOrchestrator}.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link PostConstruct} — Spring calls this after the bean is fully
 *       wired. We start the worker thread here.</li>
 *   <li>{@link PreDestroy} — Spring calls this at application shutdown
 *       (Ctrl+C, SIGTERM, container stop). We set the "keep running" flag
 *       to false and wait for the thread to exit gracefully.</li>
 * </ul>
 *
 * <h2>Error isolation</h2>
 * <p>Every iteration of the loop is wrapped in a catch-all. A single bad
 * job — malformed, missing triage row, orchestrator bug — must never kill
 * the worker. The alternative (an uncaught exception exiting the thread)
 * would silently stop the queue from draining, which is exactly the kind
 * of half-dead state that turns into a 3 AM incident.
 *
 * <h2>Correlation IDs on the worker path</h2>
 * <p>The worker isn't a servlet request, so the {@code CorrelationIdFilter}
 * doesn't run for it. But we still want each triage's log lines threaded
 * together, so we manually stuff the triage ID into MDC before the
 * orchestrator runs and clear it in a {@code finally}. Same pattern the
 * filter uses.
 *
 * <h2>Thread naming</h2>
 * <p>We name the thread {@code pager-triage-worker} so jstack, jconsole,
 * and log lines are readable when you're debugging in prod. Default Java
 * thread names like {@code Thread-42} are a small operability tax we
 * avoid by naming things.
 */
@Component
public class TriageWorker {

    private static final Logger log = LoggerFactory.getLogger(TriageWorker.class);
    private static final String THREAD_NAME = "pager-triage-worker";
    private static final long SHUTDOWN_TIMEOUT_MS = 10_000;

    private final TriageQueueConsumer consumer;
    private final TriageOrchestrator orchestrator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public TriageWorker(TriageQueueConsumer consumer, TriageOrchestrator orchestrator) {
        this.consumer = consumer;
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("worker already running; ignoring duplicate start");
            return;
        }
        thread = new Thread(this::loop, THREAD_NAME);
        thread.setDaemon(false); // non-daemon: prevent JVM exit while worker is running
        thread.start();
        log.info("triage worker started");
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("triage worker stopping — will finish current BRPOP");
        if (thread != null) {
            thread.join(SHUTDOWN_TIMEOUT_MS);
            if (thread.isAlive()) {
                log.warn("worker did not stop within {}ms — interrupting", SHUTDOWN_TIMEOUT_MS);
                thread.interrupt();
            }
        }
        log.info("triage worker stopped");
    }

    /**
     * Package-private so tests can drive it directly without starting a real thread.
     */
    void loop() {
        while (running.get()) {
            try {
                TriageJob job = consumer.poll();
                if (job == null) {
                    // Timeout, empty queue, or transient Redis error. Loop.
                    continue;
                }
                processOne(job);
            } catch (Throwable t) {
                // Absolute last-resort catch. If we get here, something
                // exotic broke (OutOfMemoryError, etc.). Log and keep going
                // — dying silently is worse than a hot loop that at least
                // shows up in logs.
                log.error("unexpected error in worker loop — will continue", t);
            }
        }
        log.info("worker loop exited cleanly");
    }

    /**
     * Package-private so tests can invoke it directly for a single job.
     */
    void processOne(TriageJob job) {
        MDC.put("triageId", job.triageId());
        MDC.put("incidentId", job.incidentId());
        try {
            orchestrator.run(job);
        } catch (Exception e) {
            // Orchestrator errors are already logged inside — don't double-log
            // the stack. Just record we've moved past this job.
            log.warn("triage {} failed; moving to next job", job.triageId());
        } finally {
            MDC.remove("triageId");
            MDC.remove("incidentId");
        }
    }
}