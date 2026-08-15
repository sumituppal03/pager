package dev.sumituppal.pager.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Hybrid retriever combining vector similarity and full-text search
 * via Reciprocal Rank Fusion (RRF).
 *
 * <h2>Why hybrid?</h2>
 * <p>Vector search alone fails at entity names ("checkout-api" vs
 * "checkout_api" produce different embeddings but should return
 * the same docs). FTS alone misses semantic similarity ("elevated
 * latency" vs "slow response"). Combining both captures both signals.
 *
 * <h2>Why RRF, not weighted score merging?</h2>
 * <p>Vector and FTS scores are on incompatible scales (0-1 cosine
 * vs unbounded ts_rank). Normalizing scores is fragile — thresholds
 * differ per corpus. RRF just uses ranks, so no normalization needed:
 * {@code score = sum(1 / (k + rank_i))} where k=60 is the standard
 * value from the original paper.
 *
 * <h2>Failure isolation</h2>
 * <p>If the embedder is down, vector search returns empty and we fall
 * back to FTS-only. If the DB is unreachable, both fail — but that's
 * a different problem class the caller has to handle.
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    // RRF constant from Cormack et al 2009. Value doesn't matter much
    // in practice; 60 is the canonical default.
    private static final int RRF_K = 60;

    private final EmbeddingClient embeddingClient;
    private final DocumentRepository documents;
    private final DocumentEmbeddingRepository embeddings;

    public HybridRetriever(
            EmbeddingClient embeddingClient,
            DocumentRepository documents,
            DocumentEmbeddingRepository embeddings) {
        this.embeddingClient = embeddingClient;
        this.documents = documents;
        this.embeddings = embeddings;
    }

    /**
     * Retrieve up to {@code limit} documents most relevant to the query.
     *
     * <p>Runs vector search and FTS in parallel-ish (sequential here for
     * simplicity; both are usually sub-100ms), then fuses via RRF.
     *
     * <p>Never throws. Returns empty list on any error.
     */
    public List<Document> retrieve(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        int fetchPerSource = Math.max(limit * 2, 10); // over-fetch for fusion

        List<String> vectorHits = safeVectorSearch(query, fetchPerSource);
        List<String> ftsHits = safeFullTextSearch(query, fetchPerSource);

        if (vectorHits.isEmpty() && ftsHits.isEmpty()) {
            log.debug("retrieval found nothing for query: {}", truncate(query));
            return List.of();
        }

        List<String> fusedIds = reciprocalRankFusion(vectorHits, ftsHits, limit);

        // Load the actual document rows in one query, preserving fusion order.
        Map<String, Document> byId = documents.findAllById(fusedIds).stream()
            .collect(Collectors.toMap(Document::getId, d -> d));

        List<Document> result = new ArrayList<>(fusedIds.size());
        for (String id : fusedIds) {
            Document doc = byId.get(id);
            if (doc != null) result.add(doc);
        }
        log.debug("retrieved {} documents for query: {} (vector={}, fts={})",
            result.size(), truncate(query), vectorHits.size(), ftsHits.size());
        return result;
    }

    private List<String> safeVectorSearch(String query, int limit) {
        try {
            float[] vec = embeddingClient.embed(query);
            String pgvec = DocumentEmbedding.formatVector(vec);
            return embeddings.findNearest(pgvec, embeddingClient.modelName(), limit);
        } catch (Exception e) {
            log.warn("vector search failed, falling back to FTS only: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> safeFullTextSearch(String query, int limit) {
        try {
            return documents.searchByFullText(query, limit);
        } catch (Exception e) {
            log.warn("FTS search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Reciprocal Rank Fusion.
     *
     * <p>For each document appearing in either list, compute
     * {@code sum(1 / (k + rank))}. Higher score = more relevant.
     *
     * <p>Returns document IDs ordered by fused score descending, capped
     * at {@code limit}.
     */
    List<String> reciprocalRankFusion(List<String> listA, List<String> listB, int limit) {
        Map<String, Double> scores = new HashMap<>();
        addRankScores(scores, listA);
        addRankScores(scores, listB);

        // Sort by score desc; LinkedHashMap preserves insertion order.
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    private static void addRankScores(Map<String, Double> scores, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            String id = ranked.get(i);
            double contribution = 1.0 / (RRF_K + i + 1);
            scores.merge(id, contribution, Double::sum);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}