package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A single triage run — one row per incident processed by the agent.
 *
 * <p>Fields map 1:1 to the {@code triage_runs} table columns. The
 * {@link Specialist} / {@link Severity} / {@link TriageStatus} enums are
 * stored as their {@code dbValue()} strings so the DB CHECK constraints
 * can validate them.
 *
 * <p>The {@code rawPayload} JSONB column uses Hibernate 6's native
 * {@code SqlTypes.JSON} — no external converter needed as of Boot 3.4.
 */
@Entity
@Table(name = "triage_runs")
public class TriageRun {

    @Id
    private String id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "incident_id", nullable = false)
    private String incidentId;

    @Column(name = "incident_url")
    private String incidentUrl;

    @Column(name = "alert_summary", nullable = false, columnDefinition = "TEXT")
    private String alertSummary;

    @Column(nullable = false)
    private String severity;   // stored as e.g. "P1"; access via severityEnum()

    private String service;

    @Column(nullable = false)
    private String status;     // stored as e.g. "queued"; access via statusEnum()

    @Column(name = "overall_confidence", precision = 4, scale = 3)
    private BigDecimal overallConfidence;

    @Column(name = "aggregated_summary", columnDefinition = "TEXT")
    private String aggregatedSummary;

    @Column(name = "slack_channel")
    private String slackChannel;

    @Column(name = "slack_message_ts")
    private String slackMessageTs;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload; // stored as JSON string; deserialized where used

    @Column(name = "total_cost_usd", precision = 10, scale = 6)
    private BigDecimal totalCostUsd;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ---------- Lifecycle ----------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("triage");
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = TriageStatus.QUEUED.dbValue();
        }
    }

    // ---------- Enum accessors ----------

    public Severity severityEnum() {
        return Severity.fromDbValue(severity);
    }

    public void severityEnum(Severity s) {
        this.severity = s.dbValue();
    }

    public TriageStatus statusEnum() {
        return TriageStatus.fromDbValue(status);
    }

    public void statusEnum(TriageStatus s) {
        this.status = s.dbValue();
    }

    // ---------- Getters & setters (idiomatic JavaBean; JPA needs them) ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }

    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String v) { this.incidentId = v; }

    public String getIncidentUrl() { return incidentUrl; }
    public void setIncidentUrl(String v) { this.incidentUrl = v; }

    public String getAlertSummary() { return alertSummary; }
    public void setAlertSummary(String v) { this.alertSummary = v; }

    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }

    public String getService() { return service; }
    public void setService(String v) { this.service = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public BigDecimal getOverallConfidence() { return overallConfidence; }
    public void setOverallConfidence(BigDecimal v) { this.overallConfidence = v; }

    public String getAggregatedSummary() { return aggregatedSummary; }
    public void setAggregatedSummary(String v) { this.aggregatedSummary = v; }

    public String getSlackChannel() { return slackChannel; }
    public void setSlackChannel(String v) { this.slackChannel = v; }

    public String getSlackMessageTs() { return slackMessageTs; }
    public void setSlackMessageTs(String v) { this.slackMessageTs = v; }

    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String v) { this.rawPayload = v; }

    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal v) { this.totalCostUsd = v; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}