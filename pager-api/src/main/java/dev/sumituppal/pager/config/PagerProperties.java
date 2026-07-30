package dev.sumituppal.pager.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Type-safe, validated configuration for the Pager app.
 *
 * <p>Backed by the {@code pager.*} keys in application.yml. Every field is
 * validated on startup — a bad or missing value fails the boot immediately
 * with a clear message, rather than surfacing hours later as a mystery
 * production incident.
 *
 * <p>Bind to environment variables via Spring's relaxed binding:
 * {@code PAGER_CONFIDENCE_AUTOPOST_THRESHOLD} → {@code pager.confidence-autopost-threshold}.
 *
 * @param confidenceAutopostThreshold  If overall confidence ≥ this, the
 *                                     aggregator posts read-only findings
 *                                     without human review. See L7 of the
 *                                     architecture study.
 * @param specialistTimeoutMs          Hard cap on any single specialist run.
 *                                     Beyond this, the orchestrator gives up
 *                                     on that specialist and aggregates
 *                                     partial results.
 * @param toolCallTimeoutMs            Hard cap on any single tool call
 *                                     (Prometheus, deploy history, etc.).
 * @param models                       LLM model routing — see {@link Models}.
 * @param dailyBudgetUsd               If the day's spend exceeds this,
 *                                     BudgetGuard blocks new specialist runs.
 * @param pagerdutyWebhookSecret       Shared secret for HMAC verification of
 *                                     inbound PagerDuty webhooks.
 */
@Validated
@ConfigurationProperties(prefix = "pager")
public record PagerProperties(

    @NotNull
    @DecimalMin(value = "0.0",  inclusive = true,  message = "confidence-autopost-threshold must be >= 0.0")
    @DecimalMax(value = "1.0",  inclusive = true,  message = "confidence-autopost-threshold must be <= 1.0")
    BigDecimal confidenceAutopostThreshold,

    @Positive(message = "specialist-timeout-ms must be positive")
    long specialistTimeoutMs,

    @Positive(message = "tool-call-timeout-ms must be positive")
    long toolCallTimeoutMs,

    @NotNull(message = "pager.models is required")
    @Valid
    Models models,

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "daily-budget-usd must be > 0")
    BigDecimal dailyBudgetUsd,

    @NotBlank(message = "pagerduty-webhook-secret must not be blank — set PAGER_PAGERDUTY_WEBHOOK_SECRET")
    String pagerdutyWebhookSecret

) {

    /**
     * Guard against accidentally shipping the default secret to prod.
     * The record's compact canonical constructor runs after Spring binds
     * every field, so this is the right place for cross-field checks.
     */
    public PagerProperties {
        if ("dev-only-change-me".equals(pagerdutyWebhookSecret)) {
            // Only fatal in prod-ish environments; dev is fine.
            String profile = System.getProperty("spring.profiles.active", "");
            if (profile.contains("prod")) {
                throw new IllegalStateException(
                    "pager.pagerduty-webhook-secret is still the dev default — " +
                    "set PAGER_PAGERDUTY_WEBHOOK_SECRET to a real secret before deploying"
                );
            }
        }
    }

    /**
     * LLM model routing config.
     *
     * @param fast     Model used for the fast specialists (symptoms, change, metrics)
     * @param quality  Model used for the comms specialist (writes user-facing text)
     * @param embed    Model used for embeddings (runbook + query vectorization)
     */
    public record Models(
        @NotBlank(message = "pager.models.fast must not be blank")
        String fast,

        @NotBlank(message = "pager.models.quality must not be blank")
        String quality,

        @NotBlank(message = "pager.models.embed must not be blank")
        String embed
    ) {}
}