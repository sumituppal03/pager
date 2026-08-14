package dev.sumituppal.pager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the frontend dashboard.
 *
 * <p>The Next.js dev server runs at {@code http://localhost:3000} and
 * makes fetch calls to the Java API at {@code http://localhost:8080}.
 * Browsers block cross-origin requests by default unless the server
 * explicitly opts in via CORS headers.
 *
 * <h2>Codespaces caveat</h2>
 * <p>When running in Codespaces, each forwarded port gets a distinct
 * dynamic subdomain under {@code app.github.dev}. We allow that pattern
 * in addition to localhost so both dev environments work without config
 * changes.
 *
 * <h2>Why {@code /api/**} only?</h2>
 * <p>Ingress ({@code /webhooks/**}) and dev ({@code /dev/**}) endpoints
 * don't need to be reachable from a browser. Restricting CORS to
 * {@code /api/**} keeps the attack surface minimal — a compromised
 * frontend can't POST to the webhook endpoint on your behalf.
 *
 * <h2>Production notes</h2>
 * <p>{@code allowedOriginPatterns} is hardcoded here. In prod, we'd
 * read this from config with the actual deployed frontend origin.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(
                "http://localhost:3000",
                "https://*.app.github.dev",
                "https://*.githubpreview.dev"
            )
            .allowedMethods("GET")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600);
    }
}