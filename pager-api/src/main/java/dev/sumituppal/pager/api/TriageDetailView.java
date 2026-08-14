package dev.sumituppal.pager.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response shape for the triage detail endpoint.
 *
 * <p>Includes everything the detail page needs to render:
 * <ul>
 *   <li>All triage-level fields (raw alert, timestamps, status, summary)</li>
 *   <li>All 5 findings (specialists + aggregator) with their content</li>
 *   <li>The notification record (gate decision + message)</li>
 *   <li>Agent events for the trace viewer's Gantt rendering</li>
 * </ul>
 */
public record TriageDetailView(
    String id,
    String incidentId,
    String alertSummary,
    String service,
    String severity,
    String status,
    String aggregatedSummary,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    List<FindingView> findings,
    NotificationView notification,   // null if no notification record exists yet
    List<AgentEventView> events
) {

    /**
     * One finding (specialist output) shape.
     * Rationale field carries the full JSON payload with raw LLM response —
     * frontend can expand it for advanced users.
     */
    public record FindingView(
        String id,
        String specialist,
        String category,
        String severity,
        BigDecimal confidence,
        String summary,
        String rationale,
        OffsetDateTime createdAt
    ) {}

    /**
     * The notification record — what the HITL gate decided to do.
     * {@code payload} carries the exact drafted message text, so approvers
     * can see what would have been sent even for AWAITING_REVIEW.
     */
    public record NotificationView(
        String id,
        String decision,
        String channel,
        String payload,
        OffsetDateTime createdAt
    ) {}

    /**
     * One row from {@code agent_events} — a span or LLM call.
     * The frontend groups these by {@code spanId} to render the trace.
     */
    public record AgentEventView(
        String id,
        OffsetDateTime ts,
        String eventType,
        String specialist,
        String spanId,
        String parentSpanId,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Integer latencyMs,
        String outcome
    ) {}
}