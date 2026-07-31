package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A single row on the observability spine — one per span start, span end,
 * LLM call, tool call, decision, escalation, or error.
 *
 * <p>Append-only. Never updated. Never deleted (except by TTL policy in
 * a later PR). Three consumers read this table:
 * <ol>
 *   <li>The trace viewer — reconstructs one triage end-to-end</li>
 *   <li>The audit trail — evidence for post-mortems</li>
 *   <li>The cost ledger — sums {@code cost_usd} by {@code specialist}</li>
 * </ol>
 *
 * <p>See PR 4a for the deliberate lack of a JPA-level FK to {@link TriageRun}
 * (hot-path insert perf).
 */
@Entity
@Table(name = "agent_events")
public class AgentEvent {

    @Id
    private String id;

    @Column(nullable = false)
    private OffsetDateTime ts;

    @Column(name = "triage_id", nullable = false)
    private String triageId;

    @Column(nullable = false)
    private String specialist;

    @Column(name = "span_id", nullable = false)
    private String spanId;

    @Column(name = "parent_span_id")
    private String parentSpanId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // llm.call fields
    private String model;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "cost_usd", precision = 10, scale = 6)
    private BigDecimal costUsd;

    // tool.call fields
    @Column(name = "tool_name")
    private String toolName;

    // timing
    @Column(name = "latency_ms")
    private Integer latencyMs;

    // decision fields
    private String outcome;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    // ---------- Lifecycle ----------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("evt");
        }
        if (ts == null) {
            ts = OffsetDateTime.now();
        }
    }

    // ---------- Enum accessors ----------

    public Specialist specialistEnum() {
        return Specialist.fromDbValue(specialist);
    }

    public void specialistEnum(Specialist s) {
        this.specialist = s.dbValue();
    }

    public AgentEventType eventTypeEnum() {
        return AgentEventType.fromDbValue(eventType);
    }

    public void eventTypeEnum(AgentEventType t) {
        this.eventType = t.dbValue();
    }

    // ---------- Getters & setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public OffsetDateTime getTs() { return ts; }
    public void setTs(OffsetDateTime v) { this.ts = v; }

    public String getTriageId() { return triageId; }
    public void setTriageId(String v) { this.triageId = v; }

    public String getSpecialist() { return specialist; }
    public void setSpecialist(String v) { this.specialist = v; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String v) { this.spanId = v; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String v) { this.parentSpanId = v; }

    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }

    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }

    public Integer getTokensIn() { return tokensIn; }
    public void setTokensIn(Integer v) { this.tokensIn = v; }

    public Integer getTokensOut() { return tokensOut; }
    public void setTokensOut(Integer v) { this.tokensOut = v; }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal v) { this.costUsd = v; }

    public String getToolName() { return toolName; }
    public void setToolName(String v) { this.toolName = v; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer v) { this.latencyMs = v; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal v) { this.confidence = v; }

    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
}