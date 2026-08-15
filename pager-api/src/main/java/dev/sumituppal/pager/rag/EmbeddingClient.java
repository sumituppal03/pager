package dev.sumituppal.pager.rag;

import java.util.List;

/**
 * Contract for anything that produces text embeddings.
 *
 * <h2>Why an interface?</h2>
 * <p>Today (PR #14) the only implementation is {@link HttpEmbeddingClient}
 * calling the Python sentence-transformers sidecar. Later we might swap
 * to OpenAI's text-embedding-3-small, Voyage's voyage-3, or a self-hosted
 * TEI (Text Embeddings Inference) server — same interface, different impl.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #embed(String)} — single text, most common case</li>
 *   <li>{@link #embedBatch(List)} — multiple texts in one call, cheaper
 *       when seeding a corpus of dozens of documents</li>
 *   <li>{@link #dimensionality()} — the fixed vector size (384 for our
 *       current model). Used to validate DB schema alignment.</li>
 *   <li>{@link #modelName()} — the model identifier for audit trails
 *       and multi-model corpus support</li>
 * </ul>
 *
 * <p>All embeddings returned are L2-normalized — cosine similarity is
 * equivalent to dot product on normalized vectors, and pgvector's
 * {@code <=>} operator (cosine distance) expects this.
 */
public interface EmbeddingClient {

    /** Embed a single text. */
    float[] embed(String text);

    /** Embed multiple texts in one call. Preferred for corpus seeding. */
    List<float[]> embedBatch(List<String> texts);

    /** Vector dimensionality — matches the pgvector column size. */
    int dimensionality();

    /** Model identifier — for audit and multi-model corpus support. */
    String modelName();
}