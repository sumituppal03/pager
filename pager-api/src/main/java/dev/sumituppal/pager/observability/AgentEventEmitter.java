package dev.sumituppal.pager.observability;

import dev.sumituppal.pager.domain.AgentEvent;
import dev.sumituppal.pager.domain.AgentEventRepository;
import dev.sumituppal.pager.domain.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Writes rows to the {@code agent_events} table — the observability spine.
 *
 * <p>Every emit call produces one durable row. Callers use the specialized
 * methods below (spanStart, spanEnd, llmCall, toolCall, decision, error)
 * rather than constructing {@link AgentEvent} directly, so the required
 * fields for each event type stay consistent across the codebase.
 *
 * <h2>Failure policy</h2>
 * <p>An event write failure must never fail the triage. Emit calls are
 * side-effects, and the triage's business outcome is captured in
 * {@code triage_runs}. If Postgres briefly rejects an event insert, we
 * log at error and continue. This is different from — and safer than —
 * making event writes part of the triage transaction: an event write
 * bug shouldn't roll back a successful triage.
 */
@Component
public class AgentEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgentEventEmitter.class);

    private final AgentEventRepository events;

    public AgentEventEmitter(AgentEventRepository events) {
        this.events = events;
    }

    /**
     * Emit a {@code span.start} event marking the beginning of a bounded
     * unit of work.
     */
    public void spanStart(SpanContext ctx) {
        emit(ctx, AgentEventType.SPAN_START, null, null, null, null, null);
    }

    /**
     * Emit a {@code span.end} event marking successful completion of a
     * span, with the wall-clock latency of the enclosed work.
     */
    public void spanEnd(SpanContext ctx, long latencyMs, String outcome) {
        emit(ctx, AgentEventType.SPAN_END, latencyMs, outcome, null, null, null);
    }

    /**
     * Emit an {@code llm.call} event capturing tokens and cost. This is
     * what the future cost ledger sums by day.
     */
    public void llmCall(SpanContext ctx, String model,
                        int tokensIn, int tokensOut,
                        BigDecimal costUsd, long latencyMs) {
        AgentEvent e = buildBase(ctx, AgentEventType.LLM_CALL);
        e.setModel(model);
        e.setTokensIn(tokensIn);
        e.setTokensOut(tokensOut);
        e.setCostUsd(costUsd);
        e.setLatencyMs((int) latencyMs);
        save(e);
    }

    /**
     * Emit a {@code tool.call} event for a tool invocation (Prometheus,
     * deploy history, etc.).
     */
    public void toolCall(SpanContext ctx, String toolName, long latencyMs, String outcome) {
        AgentEvent e = buildBase(ctx, AgentEventType.TOOL_CALL);
        e.setToolName(toolName);
        e.setLatencyMs((int) latencyMs);
        e.setOutcome(outcome);
        save(e);
    }

    /**
     * Emit a {@code decision} event — the aggregator chose to auto-post
     * vs. request approval, an escalation threshold tripped, etc.
     */
    public void decision(SpanContext ctx, String outcome, BigDecimal confidence) {
        AgentEvent e = buildBase(ctx, AgentEventType.DECISION);
        e.setOutcome(outcome);
        e.setConfidence(confidence);
        save(e);
    }

    /**
     * Emit an {@code error} event with an optional message payload.
     * Called from span cleanup when work throws.
     */
    public void error(SpanContext ctx, String message) {
        AgentEvent e = buildBase(ctx, AgentEventType.ERROR);
        e.setOutcome("error");
        if (message != null) {
            e.setPayload("{\"message\":" + jsonQuote(message) + "}");
        }
        save(e);
    }

    // ---- internal ----

    private void emit(SpanContext ctx, AgentEventType type,
                      Long latencyMs, String outcome,
                      String model, Integer tokensIn, Integer tokensOut) {
        AgentEvent e = buildBase(ctx, type);
        if (latencyMs != null) e.setLatencyMs(latencyMs.intValue());
        if (outcome != null)   e.setOutcome(outcome);
        if (model != null)     e.setModel(model);
        if (tokensIn != null)  e.setTokensIn(tokensIn);
        if (tokensOut != null) e.setTokensOut(tokensOut);
        save(e);
    }

    private AgentEvent buildBase(SpanContext ctx, AgentEventType type) {
        AgentEvent e = new AgentEvent();
        e.setTs(OffsetDateTime.now());
        e.setTriageId(ctx.triageId());
        e.specialistEnum(ctx.specialist());
        e.setSpanId(ctx.spanId());
        e.setParentSpanId(ctx.parentSpanId());
        e.eventTypeEnum(type);
        return e;
    }

    private void save(AgentEvent event) {
        try {
            events.save(event);
        } catch (Exception ex) {
            // Event writes are side-effects. A DB blip here must not
            // fail the triage. Log at error and continue.
            log.error("failed to write agent event for triage {} span {}: {}",
                event.getTriageId(), event.getSpanId(), ex.getMessage());
        }
    }

    private static String jsonQuote(String s) {
        // Minimal JSON string escaping. Full ObjectMapper would be overkill
        // here — this method only ever handles error messages.
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}