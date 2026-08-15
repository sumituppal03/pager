package dev.sumituppal.pager.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link Document}.
 *
 * <p>The interesting method is {@link #searchByFullText} — uses Postgres
 * FTS via {@code plainto_tsquery} for lexical search. Vector search
 * lives on {@link DocumentEmbeddingRepository}; the hybrid retriever
 * combines both.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    /**
     * Full-text search using the FTS column populated by V3 migration.
     * Returns document IDs ranked by ts_rank descending.
     */
    @Query(value = """
        SELECT id
        FROM documents
        WHERE content_tsv @@ plainto_tsquery('english', :query)
        ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<String> searchByFullText(@Param("query") String query, @Param("limit") int limit);
}