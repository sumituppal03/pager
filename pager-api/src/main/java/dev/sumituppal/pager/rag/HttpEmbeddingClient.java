package dev.sumituppal.pager.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the Python sidecar's POST /embed endpoint over HTTP.
 *
 * <h2>Why Java's built-in HttpClient, not OkHttp/Apache/Retrofit?</h2>
 * <p>Java 21's HttpClient is competent for one-endpoint calls. Adding a
 * third-party HTTP library for this one client would be dependency
 * inflation — HttpClient handles the JSON POST + response parse in
 * ~20 lines.
 *
 * <h2>Thread safety</h2>
 * <p>{@link HttpClient} is thread-safe by design. One instance shared
 * across all callers is correct.
 *
 * <h2>What happens when the sidecar is down?</h2>
 * <p>Throws {@link EmbeddingException}. Retrievers should catch this
 * and either fall back to FTS-only retrieval or return an empty result.
 * The specialists will still produce a finding — retrieval is a nice-
 * to-have, not a hard dependency.
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingClient.class);
    private static final String MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    private static final int DIM = 384;

    private final EmbedderProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpEmbeddingClient(EmbedderProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(props.timeoutMs(), 5000)))
            .build();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            String requestBody = objectMapper.writeValueAsString(
                new EmbedRequest(texts));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + "/embed"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> res = httpClient.send(
                req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                throw new EmbeddingException(
                    "embedder returned " + res.statusCode() + ": " + res.body());
            }

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode embeddings = root.path("embeddings");
            if (!embeddings.isArray()) {
                throw new EmbeddingException(
                    "malformed response — no 'embeddings' array");
            }

            List<float[]> result = new ArrayList<>(embeddings.size());
            for (JsonNode vec : embeddings) {
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    arr[i] = (float) vec.get(i).asDouble();
                }
                result.add(arr);
            }
            log.debug("embedded {} texts (model={}, dim={})",
                texts.size(), MODEL, DIM);
            return result;

        } catch (Exception e) {
            log.warn("embedding call failed: {}", e.getMessage());
            throw new EmbeddingException("embedding call failed", e);
        }
    }

    @Override
    public int dimensionality() {
        return DIM;
    }

    @Override
    public String modelName() {
        return MODEL;
    }

    /** Request body shape matching the FastAPI service. */
    private record EmbedRequest(List<String> texts) {}

    /** Thrown when the embedder call fails. Callers should fall back gracefully. */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}