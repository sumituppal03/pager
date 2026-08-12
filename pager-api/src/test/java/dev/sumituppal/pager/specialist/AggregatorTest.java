package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.llm.PromptTemplate;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.SpanContext;
import dev.sumituppal.pager.specialist.Aggregator.SpecialistFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Aggregator}.
 *
 * <p>Focus: category parsing (both uppercase and dbValue forms),
 * fallback behavior on LLM failure, and the quality-model tier.
 */
class AggregatorTest {

    private ChatClient chat;
    private PromptRegistry prompts;
    private AgentEventEmitter events;
    private ObjectMapper objectMapper;
    private Aggregator aggregator;

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class);
        prompts = mock(PromptRegistry.class);
        events = mock(AgentEventEmitter.class);
        objectMapper = new ObjectMapper();

        when(prompts.get("aggregator")).thenReturn(
            new PromptTemplate("aggregator", "v1",
                "Merge these: {{findingsJson}}"));

        aggregator = new Aggregator(chat, prompts, events, objectMapper);
    }

    @Test
    @DisplayName("merges 4 findings into one output with a real category")
    void mergesFindingsWithCategory() {
        stubLlmResponse("""
            {
              "category": "deploy_regression",
              "summary": "Checkout 5xx spike likely from recent deploy at 3:02 AM",
              "confidence": 0.82,
              "reasoning": "Symptoms confirms 5xx, Change points at deploy, Metrics corroborates"
            }
            """);

        SpecialistOutput output = aggregator.aggregate(
            "triage_ABC", sampleParent(), sampleInputs());

        assertThat(output.category()).isEqualTo(FindingCategory.DEPLOY_REGRESSION);
        assertThat(output.summary()).contains("deploy");
        assertThat(output.confidence()).isEqualByComparingTo("0.820");
    }

    @Test
    @DisplayName("uppercase category name also works (defensive parsing)")
    void uppercaseCategoryWorks() {
        stubLlmResponse("""
            {"category":"DEPLOY_REGRESSION","summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        SpecialistOutput output = aggregator.aggregate(
            "triage_ABC", sampleParent(), sampleInputs());

        assertThat(output.category()).isEqualTo(FindingCategory.DEPLOY_REGRESSION);
    }

    @Test
    @DisplayName("unknown category falls back to UNKNOWN")
    void unknownCategoryFallsBack() {
        stubLlmResponse("""
            {"category":"NOT_A_REAL_CATEGORY","summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        SpecialistOutput output = aggregator.aggregate(
            "triage_ABC", sampleParent(), sampleInputs());

        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
    }

    @Test
    @DisplayName("uses QUALITY model, not FAST")
    void usesQualityModel() {
        stubLlmResponse("""
            {"category":"unknown","summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        aggregator.aggregate("triage_ABC", sampleParent(), sampleInputs());

        verify(chat, times(1)).completeQuality(anyString());
    }

    @Test
    @DisplayName("LLM failure falls back to highest-confidence input summary")
    void llmFailureFallsBack() {
        when(chat.completeQuality(anyString()))
            .thenThrow(new RuntimeException("simulated LLM outage"));

        SpecialistOutput output = aggregator.aggregate(
            "triage_ABC", sampleParent(), sampleInputs());

        // Symptoms had confidence 0.85 in sampleInputs — highest.
        assertThat(output.summary()).contains("Checkout 5xx confirmed");
        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
        assertThat(output.confidence()).isEqualByComparingTo("0.0");
        assertThat(output.payload()).contains("simulated LLM outage");
    }

    @Test
    @DisplayName("malformed JSON falls back to highest-confidence input summary")
    void malformedJsonFallsBack() {
        stubLlmResponse("this is not JSON");

        SpecialistOutput output = aggregator.aggregate(
            "triage_ABC", sampleParent(), sampleInputs());

        assertThat(output.summary()).contains("Checkout 5xx confirmed");
        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
    }

    @Test
    @DisplayName("emits an llm.call event on success")
    void emitsLlmCallEvent() {
        stubLlmResponse("""
            {"category":"unknown","summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        aggregator.aggregate("triage_ABC", sampleParent(), sampleInputs());

        verify(events, times(1)).llmCall(
            org.mockito.ArgumentMatchers.any(SpanContext.class),
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong()
        );
    }

    // ----- helpers -----

    private void stubLlmResponse(String text) {
        when(chat.completeQuality(anyString())).thenReturn(
            new ChatClient.ChatCompletion(
                text, "llama-3.3-70b-versatile", 500, 100, 3000L));
    }

    private static SpanContext sampleParent() {
        return SpanContext.root("triage_ABC", Specialist.AGGREGATOR, "orchestrator");
    }

    private static List<SpecialistFinding> sampleInputs() {
        return List.of(
            new SpecialistFinding("symptoms", "Checkout 5xx confirmed",
                new BigDecimal("0.85"), "alert is specific"),
            new SpecialistFinding("change", "Possible recent deploy",
                new BigDecimal("0.40"), "no deploy data available"),
            new SpecialistFinding("metrics", "Elevated error rate",
                new BigDecimal("0.30"), "no metric data available"),
            new SpecialistFinding("comms", "Slack draft ready",
                new BigDecimal("0.70"), "alert is well-formed")
        );
    }
}