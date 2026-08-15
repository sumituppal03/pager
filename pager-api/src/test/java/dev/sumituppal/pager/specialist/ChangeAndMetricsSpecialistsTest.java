package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.llm.PromptTemplate;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.SpanContext;
import dev.sumituppal.pager.rag.HybridRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChangeSpecialist} and {@link MetricsSpecialist}.
 *
 * <p>Both extend {@link AbstractLlmSpecialist}, so most parse-path
 * behavior is already covered by {@code SymptomsSpecialistTest}. These
 * tests focus on identity, prompt-name wiring, and the event-emission
 * contract. Combined into one file to avoid triplicating identical
 * scaffolding.
 *
 * <p>The retriever is mocked with empty results — RAG integration is
 * exercised end-to-end via the manual demo rather than in unit tests.
 */
class ChangeAndMetricsSpecialistsTest {

    private ChatClient chat;
    private PromptRegistry prompts;
    private AgentEventEmitter events;
    private ObjectMapper objectMapper;
    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class);
        prompts = mock(PromptRegistry.class);
        events = mock(AgentEventEmitter.class);
        objectMapper = new ObjectMapper();
        retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
    }

    // ─────────────────────────────────────────────────────────────
    // Change specialist
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Change specialist reports Specialist.CHANGE")
    void changeKindIsChange() {
        ChangeSpecialist s = new ChangeSpecialist(chat, prompts, events, objectMapper, retriever);
        assertThat(s.kind()).isEqualTo(Specialist.CHANGE);
    }

    @Test
    @DisplayName("Change specialist looks up 'change' prompt template")
    void changeLooksUpChangePrompt() {
        when(prompts.get("change")).thenReturn(
            new PromptTemplate("change", "v1", "Analyze: {{alertSummary}} {{retrievedContext}}"));
        stubLlmResponse("""
            {"summary":"maybe a deploy","confidence":0.5,"reasoning":"y"}
            """);

        ChangeSpecialist s = new ChangeSpecialist(chat, prompts, events, objectMapper, retriever);
        SpecialistOutput out = s.analyze(sampleInput());

        assertThat(out.summary()).isEqualTo("maybe a deploy");
        verify(prompts, times(1)).get("change");
    }

    @Test
    @DisplayName("Change specialist emits an llm.call event on success")
    void changeEmitsLlmCallEvent() {
        when(prompts.get("change")).thenReturn(
            new PromptTemplate("change", "v1", "x {{alertSummary}} {{retrievedContext}}"));
        stubLlmResponse("""
            {"summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        ChangeSpecialist s = new ChangeSpecialist(chat, prompts, events, objectMapper, retriever);
        s.analyze(sampleInput());

        verify(events, times(1)).llmCall(
            any(SpanContext.class), anyString(),
            anyInt(), anyInt(), any(BigDecimal.class), anyLong());
    }

    @Test
    @DisplayName("Change specialist returns UNKNOWN on LLM failure")
    void changeSurvivesLlmFailure() {
        when(prompts.get("change")).thenReturn(
            new PromptTemplate("change", "v1", "x {{alertSummary}} {{retrievedContext}}"));
        when(chat.completeFast(anyString()))
            .thenThrow(new RuntimeException("simulated"));

        ChangeSpecialist s = new ChangeSpecialist(chat, prompts, events, objectMapper, retriever);
        SpecialistOutput out = s.analyze(sampleInput());

        assertThat(out.confidence()).isEqualByComparingTo("0.0");
        assertThat(out.payload()).contains("simulated");
    }

    // ─────────────────────────────────────────────────────────────
    // Metrics specialist
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Metrics specialist reports Specialist.METRICS")
    void metricsKindIsMetrics() {
        MetricsSpecialist s = new MetricsSpecialist(chat, prompts, events, objectMapper, retriever);
        assertThat(s.kind()).isEqualTo(Specialist.METRICS);
    }

    @Test
    @DisplayName("Metrics specialist looks up 'metrics' prompt template")
    void metricsLooksUpMetricsPrompt() {
        when(prompts.get("metrics")).thenReturn(
            new PromptTemplate("metrics", "v1", "Analyze: {{alertSummary}} {{retrievedContext}}"));
        stubLlmResponse("""
            {"summary":"traffic spike","confidence":0.6,"reasoning":"y"}
            """);

        MetricsSpecialist s = new MetricsSpecialist(chat, prompts, events, objectMapper, retriever);
        SpecialistOutput out = s.analyze(sampleInput());

        assertThat(out.summary()).isEqualTo("traffic spike");
        verify(prompts, times(1)).get("metrics");
    }

    @Test
    @DisplayName("Metrics specialist emits an llm.call event on success")
    void metricsEmitsLlmCallEvent() {
        when(prompts.get("metrics")).thenReturn(
            new PromptTemplate("metrics", "v1", "x {{alertSummary}} {{retrievedContext}}"));
        stubLlmResponse("""
            {"summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        MetricsSpecialist s = new MetricsSpecialist(chat, prompts, events, objectMapper, retriever);
        s.analyze(sampleInput());

        verify(events, times(1)).llmCall(
            any(SpanContext.class), anyString(),
            anyInt(), anyInt(), any(BigDecimal.class), anyLong());
    }

    @Test
    @DisplayName("Metrics specialist returns UNKNOWN on malformed JSON")
    void metricsSurvivesBadJson() {
        when(prompts.get("metrics")).thenReturn(
            new PromptTemplate("metrics", "v1", "x {{alertSummary}} {{retrievedContext}}"));
        stubLlmResponse("not JSON at all");

        MetricsSpecialist s = new MetricsSpecialist(chat, prompts, events, objectMapper, retriever);
        SpecialistOutput out = s.analyze(sampleInput());

        assertThat(out.confidence()).isEqualByComparingTo("0.0");
        assertThat(out.payload()).contains("error");
    }

    // ----- helpers -----

    private void stubLlmResponse(String text) {
        when(chat.completeFast(anyString())).thenReturn(
            new ChatClient.ChatCompletion(text, "llama-3.3-70b-versatile", 42, 24, 500L));
    }

    private static SpecialistInput sampleInput() {
        SpanContext parent = SpanContext.root(
            "triage_XYZ", Specialist.AGGREGATOR, "orchestrator");
        return new SpecialistInput(
            "triage_XYZ",
            "PGR-1234",
            "checkout-service 5xx spike",
            "checkout-api",
            "P2",
            parent
        );
    }
}