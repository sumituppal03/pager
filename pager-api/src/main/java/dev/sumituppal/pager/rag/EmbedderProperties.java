package dev.sumituppal.pager.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the embedder sidecar.
 *
 * <p>Follows the same pattern as {@link dev.sumituppal.pager.llm.LlmProperties}
 * — defaults in the compact constructor, no strict validators. Empty
 * baseUrl falls back to {@code http://localhost:8000}, which works for
 * both local dev and the docker-compose setup where the embedder is
 * exposed on that port.
 *
 * <h2>Codespaces note</h2>
 * <p>Inside a Codespace, {@code localhost:8000} works because Docker
 * Compose makes the embedder available on the same virtual localhost
 * as the Java process. No forwarded-URL gymnastics required.
 *
 * @param baseUrl   embedder base URL (e.g. http://localhost:8000)
 * @param timeoutMs per-request HTTP timeout
 */
@ConfigurationProperties(prefix = "pager.embedder")
public record EmbedderProperties(
    String baseUrl,
    long timeoutMs
) {
    public EmbedderProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 30_000L;
        }
    }
}