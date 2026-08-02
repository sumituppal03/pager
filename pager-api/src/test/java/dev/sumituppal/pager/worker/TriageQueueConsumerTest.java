package dev.sumituppal.pager.worker;

import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.ingress.TriageJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TriageQueueConsumer}.
 *
 * <p>We don't spin up a real Redis here — that belongs in an integration
 * test. What we prove:
 * <ul>
 *   <li>The consumer calls {@code BRPOP} on the correct queue name.</li>
 *   <li>The 5-second blocking timeout is passed through.</li>
 *   <li>A Redis exception is swallowed and returns null (so the worker
 *       loop retries rather than crashing).</li>
 *   <li>A returned job is passed through unchanged.</li>
 * </ul>
 */
class TriageQueueConsumerTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, TriageJob> template = mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, TriageJob> listOps = mock(ListOperations.class);

    private TriageQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        when(template.opsForList()).thenReturn(listOps);
        PagerProperties properties = new PagerProperties(
            new BigDecimal("0.75"),
            45000L, 15000L,
            new PagerProperties.Models("gpt-4o-mini", "gpt-4o", "text-embedding-3-small"),
            new BigDecimal("20.00"),
            "pager.triage.queue",
            "test-secret");
        consumer = new TriageQueueConsumer(template, properties);
    }

    @Test
    @DisplayName("BRPOP the configured queue with the 5-second blocking timeout")
    void pollsCorrectQueueWithTimeout() {
        when(listOps.rightPop(eq("pager.triage.queue"), any(Duration.class)))
            .thenReturn(new TriageJob("t1", "PGR1", 1));

        TriageJob job = consumer.poll();

        assertThat(job).isNotNull();
        assertThat(job.triageId()).isEqualTo("t1");
        verify(listOps).rightPop("pager.triage.queue", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("returns null when the queue is empty (timeout expires)")
    void returnsNullOnEmpty() {
        when(listOps.rightPop(any(String.class), any(Duration.class))).thenReturn(null);

        assertThat(consumer.poll()).isNull();
    }

    @Test
    @DisplayName("swallows Redis exception and returns null so the worker loop retries")
    void swallowsRedisException() {
        when(listOps.rightPop(any(String.class), any(Duration.class)))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("simulated"));

        assertThat(consumer.poll()).isNull();
    }
}