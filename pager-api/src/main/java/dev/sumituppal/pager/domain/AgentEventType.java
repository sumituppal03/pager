package dev.sumituppal.pager.domain;

/**
 * The kind of event captured on the observability spine.
 * Matches the CHECK constraint on {@code agent_events.event_type}.
 */
public enum AgentEventType {
    SPAN_START("span.start"),
    SPAN_END("span.end"),
    LLM_CALL("llm.call"),
    TOOL_CALL("tool.call"),
    DECISION("decision"),
    ESCALATION("escalation"),
    ERROR("error");

    private final String dbValue;

    AgentEventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static AgentEventType fromDbValue(String value) {
        for (AgentEventType t : values()) {
            if (t.dbValue.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown AgentEventType dbValue: " + value);
    }
}