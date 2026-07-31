package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A pending / decided approval for a suggested write-action against production.
 *
 * <p>By design, agents never execute write-actions autonomously — see L7 of
 * the architecture study. The agent's aggregator writes a row here with the
 * suggested action; a human then approves or rejects via the dashboard
 * (later PR). Only approved rows are dispatched.
 *
 * <p>{@code suggestedAction} is stored as JSONB so we can encode arbitrary
 * action shapes without schema changes: e.g.
 * {@code {"action": "rollback", "service": "checkout", "revision": "abc123"}}.
 */
@Entity
@Table(name = "hitl_approvals")
public class HitlApproval {

    @Id
    private String id;

    @Column(name = "triage_id", nullable = false)
    private String triageId;

    @Column(name = "finding_id")
    private String findingId;

    @Column(name = "suggested_action", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String suggestedAction; // JSON string

    @Column(name = "action_description", nullable = false, columnDefinition = "TEXT")
    private String actionDescription;

    @Column(nullable = false)
    private String outcome;         // enum stored via outcomeEnum()

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ---------- Lifecycle ----------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("hitl");
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (outcome == null) {
            outcome = HitlApprovalOutcome.PENDING.dbValue();
        }
    }

    // ---------- Enum accessors ----------

    public HitlApprovalOutcome outcomeEnum() {
        return HitlApprovalOutcome.fromDbValue(outcome);
    }

    public void outcomeEnum(HitlApprovalOutcome o) {
        this.outcome = o.dbValue();
    }

    // ---------- Getters & setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTriageId() { return triageId; }
    public void setTriageId(String v) { this.triageId = v; }

    public String getFindingId() { return findingId; }
    public void setFindingId(String v) { this.findingId = v; }

    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String v) { this.suggestedAction = v; }

    public String getActionDescription() { return actionDescription; }
    public void setActionDescription(String v) { this.actionDescription = v; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { this.approvedBy = v; }

    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime v) { this.approvedAt = v; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}