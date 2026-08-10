package dev.sumituppal.pager.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pager.llm")
public record LlmProperties(
    String apiKey,
    String baseUrl,
    double temperature,
    long timeoutMs
) {

    public LlmProperties {
        if (apiKey == null) apiKey = "";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.groq.com/openai/v1";
        if (timeoutMs <= 0) timeoutMs = 30_000L;
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(
                "pager.llm.temperature must be between 0.0 and 2.0, got " + temperature);
        }
    }
}