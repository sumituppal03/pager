package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link KnowledgeChunk}.
 *
 * <p>This repository intentionally exposes only ingestion-side operations
 * ({@code save}, {@code deleteBy…}). The retrieval side — hybrid vector
 * + full-text search with reciprocal rank fusion — needs native SQL
 * that Spring Data can't derive from a method name, so it lives in a
 * dedicated {@code KnowledgeRetriever} class in the RAG PR.
 *
 * <p>Keeping the two concerns (ingestion vs retrieval) separate makes
 * the boundaries obvious in the codebase.
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String> {

    long deleteBySourceTypeAndSourceId(String sourceType, String sourceId);

    List<KnowledgeChunk> findBySourceTypeAndSourceIdOrderByChunkIndexAsc(
        String sourceType, String sourceId);
}