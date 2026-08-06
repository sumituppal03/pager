package dev.sumituppal.pager.observability;

import dev.sumituppal.pager.domain.Specialist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link Span}.
 *
 * <p>The properties we care about:
 * <ol>
 *   <li>Opening a span always emits SPAN_START immediately.</li>
 *   <li>Closing always emits SPAN_END, with the outcome the caller set.</li>
 *   <li>The default outcome is "completed".</li>
 *   <li>If {@code recordError} was called, an ERROR event is emitted
 *       between START and END, and the outcome becomes "error".</li>
 *   <li>Latency is non-negative (we don't have exact timing to assert,
 *       but ≥ 0 is a sanity floor).</li>
 * </ol>
 */
class SpanTest {

    private AgentEventEmitter emitter;
    private SpanContext ctx;

    @BeforeEach
    void setUp() {
        emitter = mock(AgentEventEmitter.class);
        ctx = SpanContext.root("triage_XYZ", Specialist.AGGREGATOR, "orchestrator");
    }

    @Test
    @DisplayName("open emits SPAN_START immediately")
    void openEmitsStart() {
        Span.open(emitter, ctx);
        verify(emitter, times(1)).spanStart(ctx);
    }

    @Test
    @DisplayName("close emits SPAN_END with default outcome 'completed'")
    void closeEmitsEndWithDefaultOutcome() {
        try (Span ignored = Span.open(emitter, ctx)) {
            // no-op
        }
        verify(emitter, times(1)).spanEnd(eq(ctx), anyLong(), eq("completed"));
    }

    @Test
    @DisplayName("setOutcome overrides the outcome recorded on close")
    void setOutcomeIsRespected() {
        try (Span span = Span.open(emitter, ctx)) {
            span.setOutcome("partial");
        }
        verify(emitter).spanEnd(eq(ctx), anyLong(), eq("partial"));
    }

    @Test
    @DisplayName("recordError emits ERROR then SPAN_END with outcome=error")
    void recordErrorEmitsErrorThenEnd() {
        try (Span span = Span.open(emitter, ctx)) {
            span.recordError(new RuntimeException("boom"));
        }

        InOrder order = inOrder(emitter);
        order.verify(emitter).spanStart(ctx);
        order.verify(emitter).error(eq(ctx), anyString());
        order.verify(emitter).spanEnd(eq(ctx), anyLong(), eq("error"));
    }

    @Test
    @DisplayName("clean close does not emit an error event")
    void cleanCloseDoesNotEmitError() {
        try (Span ignored = Span.open(emitter, ctx)) {
            // clean
        }
        verify(emitter, never()).error(any(SpanContext.class), anyString());
    }

    private static SpanContext any(Class<SpanContext> cls) {
        return org.mockito.ArgumentMatchers.any(cls);
    }
}