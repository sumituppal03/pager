package dev.sumituppal.pager.rag;

import dev.sumituppal.pager.domain.IdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A retrievable text document — runbook, past post-mortem, service README.
 *
 * <p>Deliberately simple: title + content + kind + metadata JSON.
 * The FTS column {@code content_tsv} is populated by a generated
 * column at the DB level (see V3 migration), so we don't need a
 * JPA-level mapping for it.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    private String id;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = IdGenerator.generate("doc");
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (metadata == null) metadata = "{}";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}