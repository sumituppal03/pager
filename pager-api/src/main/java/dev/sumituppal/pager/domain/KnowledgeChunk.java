package dev.sumituppal.pager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

/**
 * A chunk of a runbook, post-mortem, or arch doc — the RAG layer's unit
 * of retrieval.
 *
 * <h2>The pgvector column trick</h2>
 * Hibernate doesn't have a native {@code vector} type. There are two
 * pragmatic options:
 * <ol>
 *   <li>Add a dedicated pgvector-Hibernate library (extra dep, more magic).</li>
 *   <li>Store the vector as {@code String} in Java, use
 *       {@link ColumnTransformer} to serialize/deserialize on read/write,
 *       and expose a helper method that returns {@code float[]}.</li>
 * </ol>
 * We take option (2) because it keeps the dependency graph minimal and
 * the mapping explicit. The wire format is Postgres's vector syntax:
 * {@code [0.123, -0.456, 0.789, ...]} — a bracketed comma-separated
 * list of floats.
 *
 * <p>Ingestion (later PR) computes the embedding, formats it as this
 * string via {@link #encodeEmbedding(float[])}, and sets it. Query-side
 * retrieval will use native queries or a Spring Data extension —
 * that's the RAG PR's problem, not this one.
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    private String id;

    @Column(name = "source_type", nullable = false)
    private String sourceType;   // 'runbook' | 'postmortem' | 'arch_doc'

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    private String service;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * The embedding, as pgvector's bracketed string format, e.g.
     * {@code "[0.123,-0.456,0.789]"}.
     *
     * <p>We tell Hibernate to cast to {@code vector} on write and to
     * {@code text} on read, so the Java side just deals with strings.
     * The {@code jdbcType = "OTHER"} comment on {@link Column} isn't
     * needed here because the type is coerced by the transformer.
     */
    @Column(columnDefinition = "vector(1536)")
    @ColumnTransformer(
        read = "embedding::text",
        write = "?::vector"
    )
    private String embedding;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ---------- Lifecycle ----------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = IdGenerator.generate("chunk");
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    // ---------- Embedding conversion helpers ----------

    /**
     * Encode a float[] as the pgvector string format the DB expects.
     * Rounds to 6 decimal places — plenty of precision for cosine sim.
     */
    public static String encodeEmbedding(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Decode the DB's string format back to a float[]. Handy for tests
     * and for retrieval-side reranking.
     */
    public static float[] decodeEmbedding(String encoded) {
        if (encoded == null) return null;
        String trimmed = encoded.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            throw new IllegalArgumentException("Not a pgvector-encoded string: " + encoded);
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    @Transient
    public float[] getEmbeddingVector() {
        return decodeEmbedding(embedding);
    }

    public void setEmbeddingVector(float[] vector) {
        this.embedding = encodeEmbedding(vector);
    }

    // ---------- Getters & setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { this.sourceId = v; }

    public String getService() { return service; }
    public void setService(String v) { this.service = v; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int v) { this.chunkIndex = v; }

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String v) { this.embedding = v; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer v) { this.tokenCount = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}