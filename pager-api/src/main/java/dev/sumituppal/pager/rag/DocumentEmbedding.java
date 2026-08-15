package dev.sumituppal.pager.rag;

import dev.sumituppal.pager.domain.IdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * An embedding of a {@link Document} by some model.
 *
 * <p>The {@code embedding} column is a pgvector {@code vector(384)}.
 * Hibernate doesn't have a native pgvector type — we store the
 * vector as a String in the JPA layer (formatted like
 * {@code "[0.1,0.2,...]"}) and the DB parses it. This is standard
 * pgvector integration on Spring/Hibernate.
 *
 * <p>The hot-path vector similarity query happens via native SQL in
 * {@link DocumentEmbeddingRepository#findNearest}, bypassing JPA
 * entity mapping entirely.
 */
@Entity
@Table(name = "document_embeddings")
public class DocumentEmbedding {

    @Id
    private String id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(nullable = false)
    private String model;

    // pgvector column — stored as text in the JPA layer, parsed by
    // Postgres. When inserting we format as "[0.1,0.2,...]".
    @Column(nullable = false, columnDefinition = "vector(384)")
    private String embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = IdGenerator.generate("emb");
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String v) { this.documentId = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String v) { this.embedding = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    /** Format a float array as pgvector text: {@code "[0.1,0.2,...]"}. */
    public static String formatVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }
}