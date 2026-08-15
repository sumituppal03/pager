package dev.sumituppal.pager.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repository for {@link DocumentEmbedding}.
 *
 * <p>The hot-path query is {@link #findNearest} — vector similarity
 * search using pgvector's {@code <=>} operator (cosine distance).
 * The HNSW index makes this fast even at millions of vectors.
 *
 * <p>For writes we bypass Hibernate's type inference on the pgvector
 * column via {@link #insertNative}. Hibernate 6 doesn't know how to
 * bind a {@code String} parameter to a {@code vector} column type,
 * so {@code save()} on the entity fails silently. The native insert
 * with an explicit {@code CAST(? AS vector)} works.
 */
@Repository
public interface DocumentEmbeddingRepository extends JpaRepository<DocumentEmbedding, String> {

    @Query(value = """
        SELECT document_id
        FROM document_embeddings
        WHERE model = :model
        ORDER BY embedding <=> CAST(:queryVector AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<String> findNearest(
        @Param("queryVector") String queryVector,
        @Param("model") String model,
        @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO document_embeddings (id, document_id, model, embedding, created_at)
        VALUES (:id, :documentId, :model, CAST(:embedding AS vector), NOW())
        """, nativeQuery = true)
    void insertNative(
        @Param("id") String id,
        @Param("documentId") String documentId,
        @Param("model") String model,
        @Param("embedding") String embedding);

    boolean existsByDocumentIdAndModel(String documentId, String model);
}
