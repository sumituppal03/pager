package dev.sumituppal.pager.ingress;

import dev.sumituppal.pager.config.PagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes triage jobs onto a Redis list (used as a work queue).
 *
 * <p>Why a Redis list and not a stream / Pub/Sub / BullMQ-style hash?
 * <ul>
 *   <li><strong>List (LPUSH / BRPOP)</strong>: simplest possible queue,
 *       zero external libraries beyond Spring Data Redis. Consumer
 *       blocks on {@code BRPOP} until a job arrives. Perfect for
 *       a single-consumer worker.</li>
 *   <li><strong>Streams</strong>: better for multi-consumer, at-least-once
 *       with consumer groups. Overkill for our current shape and adds
 *       ceremony around ACKs.</li>
 *   <li><strong>Pub/Sub</strong>: fire-and-forget, zero durability. If
 *       the worker's down, the job is lost. Not acceptable.</li>
 * </ul>
 *
 * <p>The queue is only a <em>signal</em>. Postgres already has the triage
 * row with {@code status='queued'} before we ever call this method. If
 * Redis is momentarily down, the worker's periodic sweep (later PR) will
 * find and process queued rows.
 */
@Component
public class TriageQueueProducer {

    private static final Logger log = LoggerFactory.getLogger(TriageQueueProducer.class);

    private final RedisTemplate<String, TriageJob> redisTemplate;
    private final PagerProperties properties;

    public TriageQueueProducer(
            RedisTemplate<String, TriageJob> triageQueueTemplate,
            PagerProperties properties) {
        this.redisTemplate = triageQueueTemplate;
        this.properties = properties;
    }

    /**
     * Enqueue a job for the worker.
     *
     * <p>Uses {@code LPUSH} to add to the head of the list; the worker
     * uses {@code BRPOP} to consume from the tail. That's a standard
     * FIFO queue on Redis lists.
     *
     * <p>Any failure here is <em>logged and swallowed</em>. Postgres
     * already has the triage row; a Redis outage should not fail the
     * webhook. The worker's periodic sweep is the durability backstop.
     */
    public void enqueue(TriageJob job) {
        try {
            Long newSize = redisTemplate.opsForList()
                    .leftPush(properties.queueName(), job);
            log.info("enqueued triage {} to {} (queue size now {})",
                    job.triageId(), properties.queueName(), newSize);
        } catch (Exception e) {
            log.error("failed to enqueue triage {} to Redis — worker will pick it up on sweep",
                    job.triageId(), e);
        }
    }
}