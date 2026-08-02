package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.ingress.TriageJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reads {@link TriageJob}s off the Redis queue.
 *
 * <p>Symmetric with {@code TriageQueueProducer}: producer LPUSHes to the head,
 * consumer BRPOPs from the tail — that's a standard FIFO queue on Redis lists.
 *
 * <p>Why {@code BRPOP} (blocking) and not {@code RPOP} (non-blocking)?
 * <ul>
 *   <li>{@code RPOP} returns immediately with null if the list is empty,
 *       forcing us to sleep-and-retry — a CPU-burning polling loop.</li>
 *   <li>{@code BRPOP} blocks the calling thread inside Redis until either
 *       a job arrives or the timeout expires. Redis wakes us the instant
 *       {@code LPUSH} runs. Zero polling overhead, sub-millisecond latency.</li>
 * </ul>
 *
 * <p>The timeout matters: too short and we churn Redis with reconnects;
 * too long and shutdowns take forever. Five seconds is the industry-standard
 * middle. On a graceful shutdown, we wait at most 5s for the current BRPOP
 * to return, then exit the worker loop.
 */
@Component
public class TriageQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(TriageQueueConsumer.class);
    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(5);

    private final RedisTemplate<String, TriageJob> template;
    private final PagerProperties properties;

    public TriageQueueConsumer(
            RedisTemplate<String, TriageJob> triageQueueTemplate,
            PagerProperties properties) {
        this.template = triageQueueTemplate;
        this.properties = properties;
    }

    /**
     * Block waiting for the next job, up to {@link #BLOCKING_TIMEOUT}.
     *
     * @return the next job, or {@code null} if none arrived within the timeout.
     *         Null is normal — the caller should just loop and call again.
     */
    public TriageJob poll() {
        try {
            TriageJob job = template.opsForList()
                    .rightPop(properties.queueName(), BLOCKING_TIMEOUT);
            if (job != null) {
                log.debug("consumed triage {} from {}",
                        job.triageId(), properties.queueName());
            }
            return job;
        } catch (Exception e) {
            // Redis briefly down, DNS blip, etc. Log at warn (not error —
            // this is expected under normal outages) and return null so the
            // worker loop just retries after a brief pause.
            log.warn("failed to poll Redis queue {} — will retry",
                    properties.queueName(), e);
            return null;
        }
    }
}