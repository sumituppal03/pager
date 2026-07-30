package dev.sumituppal.pager.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PagerProperties}.
 *
 * <p>These tests exercise the actual {@code @ConfigurationProperties}
 * binding and {@code @Validated} pipeline — the same code path that
 * runs at production startup.
 *
 * <p>We use {@code hasStackTraceContaining} rather than {@code hasMessageContaining}
 * because Spring wraps the validation failure like this:
 * <pre>
 *   BeanCreationException            ← top-level, generic message
 *     └─ BindException               ← Spring Boot binding wrapper
 *          └─ BindValidationException ← "here are the field errors"
 *               └─ ConstraintViolation ← "confidence-autopost-threshold must be <= 1.0"
 * </pre>
 * The field names we care about live in that innermost layer.
 * {@code hasStackTraceContaining} walks the full chain of causes.
 *
 * <p>We deliberately do <em>not</em> use {@code @SpringBootApplication}
 * or {@code @SpringBootTest}. Those pull in the full autoconfigure
 * pipeline (JPA, Redis, web server) which would require live services
 * to run. A configuration test should have zero external dependencies.
 */
class PagerPropertiesTest {

    @Test
    @DisplayName("valid properties bind successfully")
    void validPropertiesBind() {
        try (var ctx = boot(
            "pager.confidence-autopost-threshold=0.75",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret=some-real-secret-value")) {

            PagerProperties props = ctx.getBean(PagerProperties.class);

            assertThat(props.confidenceAutopostThreshold()).isEqualByComparingTo("0.75");
            assertThat(props.specialistTimeoutMs()).isEqualTo(45_000L);
            assertThat(props.toolCallTimeoutMs()).isEqualTo(15_000L);
            assertThat(props.models().fast()).isEqualTo("gpt-4o-mini");
            assertThat(props.models().quality()).isEqualTo("gpt-4o");
            assertThat(props.models().embed()).isEqualTo("text-embedding-3-small");
            assertThat(props.dailyBudgetUsd()).isEqualByComparingTo("20.00");
            assertThat(props.pagerdutyWebhookSecret()).isEqualTo("some-real-secret-value");
        }
    }

    @Test
    @DisplayName("blank webhook secret fails startup")
    void blankSecretFailsStartup() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=0.75",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret="))
            .hasStackTraceContaining("pagerduty-webhook-secret");
    }

    @Test
    @DisplayName("confidence threshold above 1.0 fails startup")
    void thresholdAboveOneFails() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=1.5",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret=some-secret"))
            .hasStackTraceContaining("confidence-autopost-threshold");
    }

    @Test
    @DisplayName("negative threshold fails startup")
    void negativeThresholdFails() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=-0.1",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret=some-secret"))
            .hasStackTraceContaining("confidence-autopost-threshold");
    }

    @Test
    @DisplayName("zero or negative timeouts fail startup")
    void nonPositiveTimeoutFails() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=0.75",
            "pager.specialist-timeout-ms=0",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret=some-secret"))
            .hasStackTraceContaining("specialist-timeout-ms");
    }

    @Test
    @DisplayName("zero daily budget fails startup")
    void zeroBudgetFails() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=0.75",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=gpt-4o-mini",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=0",
            "pager.pagerduty-webhook-secret=some-secret"))
            .hasStackTraceContaining("daily-budget-usd");
    }

    @Test
    @DisplayName("blank model name fails startup")
    void blankModelFails() {
        assertThatThrownBy(() -> boot(
            "pager.confidence-autopost-threshold=0.75",
            "pager.specialist-timeout-ms=45000",
            "pager.tool-call-timeout-ms=15000",
            "pager.models.fast=",
            "pager.models.quality=gpt-4o",
            "pager.models.embed=text-embedding-3-small",
            "pager.daily-budget-usd=20.00",
            "pager.pagerduty-webhook-secret=some-secret"))
            .hasStackTraceContaining("models.fast");
    }

    // ─────────────────────────────────────────────────────────────
    // Test harness: bind @ConfigurationProperties without booting
    // the full Spring Boot app. Fast, no external services required.
    // ─────────────────────────────────────────────────────────────

    private static ConfigurableApplicationContext boot(String... properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

        MapPropertySource propertySource = new MapPropertySource(
            "test-props", toMap(properties));
        ctx.getEnvironment().getPropertySources().addFirst(propertySource);

        ctx.register(MinimalConfig.class);
        ctx.refresh();
        return ctx;
    }

    @Configuration
    @EnableConfigurationProperties(PagerProperties.class)
    static class MinimalConfig { }

    private static Map<String, Object> toMap(String[] props) {
        Map<String, Object> map = new HashMap<>();
        for (String kv : props) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            map.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return map;
    }
}