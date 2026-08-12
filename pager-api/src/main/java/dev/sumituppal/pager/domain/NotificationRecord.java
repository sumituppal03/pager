package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One row per triage-notification decision.
 *
 * <p>Persisted regardless of whether a real message went out — the
 * decision itself is the artifact, and the payload is stored even for
 * awaiting-review so approvers see the exact draft.
 */
@Entity
@Table(name = "notification_records")
public class NotificationRecord {

    @Id
    private String id;

    @Column(name = "triage_id", nullable = false)
    private String triageId;

    @Column(nullable = false)
    private String decision; // enum stored via decisionEnum()

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("notif");
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ---------- Enum accessor ----------
    public NotificationDecision decisionEnum() {
        return NotificationDecision.fromDbValue(decision);
    }

    public void decisionEnum(NotificationDecision d) {
        this.decision = d.dbValue();
    }

    // ---------- Getters & setters ----------
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTriageId() { return triageId; }
    public void setTriageId(String v) { this.triageId = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}