package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A single finding produced by one specialist during triage.
 *
 * <p>Multiple findings roll up into an aggregated triage report. Each
 * carries its own severity, category, confidence, evidence link, and
 * rationale — the fields the aggregator uses to merge, deduplicate,
 * and score cross-specialist agreement.
 *
 * <p>Not a JPA relationship to {@link TriageRun}. We hold {@code triageId}
 * as a plain string. Reasons:
 * <ul>
 *   <li>Findings are always loaded via repository queries (never lazily
 *       through TriageRun.getFindings()) — no ORM traversal needed.</li>
 *   <li>Bidirectional {@code @OneToMany} + {@code @ManyToOne} is the
 *       largest source of subtle JPA bugs (N+1 queries, LazyInit
 *       exceptions in serialization). We avoid it entirely.</li>
 * </ul>
 * The DB-level FK still exists (see V1__initial_schema.sql), so referential
 * integrity holds. We just don't lean on JPA to enforce it.
 */
@Entity
@Table(name = "findings")
public class Finding {

    @Id
    private String id;

    @Column(name = "triage_id", nullable = false)
    private String triageId;

    @Column(nullable = false)
    private String specialist;   // enum stored via specialistEnum()

    @Column(nullable = false)
    private String severity;     // enum stored via severityEnum()

    @Column(nullable = false)
    private String category;     // enum stored via categoryEnum()

    private String service;

    @Column(name = "evidence_ts")
    private OffsetDateTime evidenceTs;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "evidence_url")
    private String evidenceUrl;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "agreement_count", nullable = false)
    private int agreementCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ---------- Lifecycle ----------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("fnd");
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ---------- Enum accessors ----------

    public Specialist specialistEnum() {
        return Specialist.fromDbValue(specialist);
    }

    public void specialistEnum(Specialist s) {
        this.specialist = s.dbValue();
    }

    public Severity severityEnum() {
        return Severity.fromDbValue(severity);
    }

    public void severityEnum(Severity s) {
        this.severity = s.dbValue();
    }

    public FindingCategory categoryEnum() {
        return FindingCategory.fromDbValue(category);
    }

    public void categoryEnum(FindingCategory c) {
        this.category = c.dbValue();
    }

    // ---------- Getters & setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTriageId() { return triageId; }
    public void setTriageId(String v) { this.triageId = v; }

    public String getSpecialist() { return specialist; }
    public void setSpecialist(String v) { this.specialist = v; }

    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }

    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }

    public String getService() { return service; }
    public void setService(String v) { this.service = v; }

    public OffsetDateTime getEvidenceTs() { return evidenceTs; }
    public void setEvidenceTs(OffsetDateTime v) { this.evidenceTs = v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }

    public String getRationale() { return rationale; }
    public void setRationale(String v) { this.rationale = v; }

    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String v) { this.evidenceUrl = v; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal v) { this.confidence = v; }

    public int getAgreementCount() { return agreementCount; }
    public void setAgreementCount(int v) { this.agreementCount = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}