package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Post-hoc human feedback on a specific finding.
 *
 * <p>Distinct from {@link HitlApproval}, which is *before* the fact — an
 * approval gate on an action about to execute. This is *after* the fact —
 * a human marking whether the agent's finding was correct, partially
 * correct, or wrong, plus an optional free-text correction.
 *
 * <p>This is the raw feedback signal that fuels continuous learning: the
 * PR that adds retrieval-based reweighting reads from this table.
 */
@Entity
@Table(name = "hitl_feedback")
public class HitlFeedback {

    @Id
    private String id;

    @Column(name = "triage_id", nullable = false)
    private String triageId;

    @Column(name = "finding_id")
    private String findingId;

    @Column
    private String correctness; // 'correct' | 'partially_correct' | 'wrong' | null

    @Column(name = "correction_text", columnDefinition = "TEXT")
    private String correctionText;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("fb");
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ---------- Getters & setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTriageId() { return triageId; }
    public void setTriageId(String v) { this.triageId = v; }

    public String getFindingId() { return findingId; }
    public void setFindingId(String v) { this.findingId = v; }

    public String getCorrectness() { return correctness; }
    public void setCorrectness(String v) { this.correctness = v; }

    public String getCorrectionText() { return correctionText; }
    public void setCorrectionText(String v) { this.correctionText = v; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String v) { this.submittedBy = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}