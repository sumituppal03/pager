package dev.sumituppal.pager.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PromptRegistry}.
 *
 * <p>We test via {@link PromptTemplate} directly and via a hand-instantiated
 * registry rather than a Spring context, because the class-under-test only
 * touches classpath resources — no need to spin up a full application context
 * for these assertions.
 */
class PromptRegistryTest {

    @Test
    @DisplayName("loads the health-check.v1 template from classpath and renders it")
    void loadsAndRendersHealthCheck() throws Exception {
        PromptRegistry registry = new PromptRegistry();
        registry.loadAll();

        PromptTemplate template = registry.get("health-check");
        assertThat(template.name()).isEqualTo("health-check");
        assertThat(template.version()).isEqualTo("v1");
        assertThat(template.id()).isEqualTo("health-check.v1");

        String rendered = template.render(Map.of("alertSummary", "checkout 5xx spike"));
        assertThat(rendered).contains("checkout 5xx spike");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    @DisplayName("unknown template name throws")
    void unknownNameThrows() throws Exception {
        PromptRegistry registry = new PromptRegistry();
        registry.loadAll();

        assertThatThrownBy(() -> registry.get("does-not-exist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no prompt registered");
    }

    @Test
    @DisplayName("unknown version throws")
    void unknownVersionThrows() throws Exception {
        PromptRegistry registry = new PromptRegistry();
        registry.loadAll();

        assertThatThrownBy(() -> registry.get("health-check", "v99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no prompt registered");
    }

    @Test
    @DisplayName("missing variable during render throws with helpful message")
    void missingVariableThrows() throws Exception {
        PromptRegistry registry = new PromptRegistry();
        registry.loadAll();
        PromptTemplate template = registry.get("health-check");

        assertThatThrownBy(() -> template.render(Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alertSummary")
            .hasMessageContaining("health-check.v1");
    }

    @Test
    @DisplayName("PromptTemplate.render escapes $ in values so regex replacement is safe")
    void renderEscapesDollarSign() {
        PromptTemplate template = new PromptTemplate(
            "test", "v1", "Value: {{v}}");

        // A raw $ would be interpreted as a regex back-reference and blow up.
        // We use Matcher.quoteReplacement internally to prevent this.
        String rendered = template.render(Map.of("v", "$100 payment"));
        assertThat(rendered).isEqualTo("Value: $100 payment");
    }

    @Test
    @DisplayName("PromptTemplate.render handles multiple occurrences of same variable")
    void renderHandlesMultipleOccurrences() {
        PromptTemplate template = new PromptTemplate(
            "test", "v1", "{{x}} and {{x}} again");

        String rendered = template.render(Map.of("x", "hello"));
        assertThat(rendered).isEqualTo("hello and hello again");
    }
}