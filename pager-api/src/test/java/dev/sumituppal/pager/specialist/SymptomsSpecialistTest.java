package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.llm.PromptTemplate;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.SpanContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
 * Tests for {@link SymptomsSpecialist}.
 *
 * <p>All LLM interaction is mocked. The specialist under test is
 * exercised through its full analyze path so we cover:
 * <ol>
 *   <li>Happy JSON response is parsed correctly.</li>
 *   <li>Response wrapped in Markdown code fences is unwrapped.</li>
 *   <li>Malformed JSON returns an UNKNOWN output (never throws).</li>
 *   <li>Confidence values outside 0.0-1.0 are clamped, not rejected.</li>
 *   <li>An LLM call failure returns UNKNOWN with the error details.</li>
 *   <li>An llm.call event is emitted with token counts.</li>
 * </ol>
 *
 * <p>Note that this specialist always produces findings with
 * {@link FindingCategory#UNKNOWN} — causal categorization is not
 * its responsibility. The Aggregator (PR #11) will map observations
 * to specific causes.
 */
class SymptomsSpecialistTest {

    private ChatClient chat;
    private PromptRegistry prompts;
    private AgentEventEmitter events;
    private ObjectMapper objectMapper;
    private SymptomsSpecialist specialist;

    private static final String STUB_PROMPT_BODY =
        "Analyze: {{alertSummary}} for {{service}} at {{severity}} ({{incidentId}})";

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class);
        prompts = mock(PromptRegistry.class);
        events = mock(AgentEventEmitter.class);
        objectMapper = new ObjectMapper();

        when(prompts.get("symptoms")).thenReturn(
            new PromptTemplate("symptoms", "v1", STUB_PROMPT_BODY));

        specialist = new SymptomsSpecialist(chat, prompts, events, objectMapper);
    }

    @Test
    @DisplayName("kind() reports Specialist.SYMPTOMS")
    void kindReportsSymptoms() {
        assertThat(specialist.kind()).isEqualTo(Specialist.SYMPTOMS);
    }

    @Test
    @DisplayName("clean JSON response parses to a finding with UNKNOWN category and confidence")
    void cleanJsonParsesCorrectly() {
        String llmResponse = """
            {
              "summary": "Checkout service returning 5xx errors at elevated rate",
              "confidence": 0.85,
              "reasoning": "The alert mentions 5xx spike explicitly"
            }
            """;
        stubLlmResponse(llmResponse);

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
        assertThat(output.summary()).contains("Checkout service");
        assertThat(output.confidence()).isEqualByComparingTo("0.850");
        assertThat(output.payload()).contains("5xx spike explicitly");
    }

    @Test
    @DisplayName("response wrapped in ```json fences is parsed correctly")
    void codeFencesAreStripped() {
        String llmResponse = """
```json
            {
              "summary": "wrapped in fences",
              "confidence": 0.6,
              "reasoning": "test"
            }
```
            """;
        stubLlmResponse(llmResponse);

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.summary()).isEqualTo("wrapped in fences");
        assertThat(output.confidence()).isEqualByComparingTo("0.600");
    }

    @Test
    @DisplayName("response wrapped in generic ``` fences (no lang) is also parsed")
    void plainCodeFencesAreStripped() {
        String llmResponse = "```\n" +
            "{\"summary\":\"plain\",\"confidence\":0.5,\"reasoning\":\"x\"}\n" +
            "```";
        stubLlmResponse(llmResponse);

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.summary()).isEqualTo("plain");
    }

    @Test
    @DisplayName("confidence above 1.0 is clamped to 1.0")
    void confidenceAboveOneIsClamped() {
        stubLlmResponse("""
            {"summary":"x","confidence":1.5,"reasoning":"y"}
            """);

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.confidence()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("confidence below 0.0 is clamped to 0.0")
    void confidenceBelowZeroIsClamped() {
        stubLlmResponse("""
            {"summary":"x","confidence":-0.3,"reasoning":"y"}
            """);

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.confidence()).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("malformed JSON returns UNKNOWN, does not throw")
    void malformedJsonReturnsUnknown() {
        stubLlmResponse("this is not JSON at all");

        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
        assertThat(output.confidence()).isEqualByComparingTo("0.000");
        assertThat(output.payload()).contains("error");
    }

    @Test
    @DisplayName("LLM call throwing returns UNKNOWN, does not propagate")
    void llmFailureReturnsUnknown() {
        when(chat.completeFast(anyString()))
            .thenThrow(new RuntimeException("simulated Groq 500"));

        // Must NOT throw
        SpecialistOutput output = specialist.analyze(sampleInput());

        assertThat(output.category()).isEqualTo(FindingCategory.UNKNOWN);
        assertThat(output.payload()).contains("simulated Groq 500");
    }

    @Test
    @DisplayName("emits an llm.call event with token counts on success")
    void emitsLlmCallEvent() {
        stubLlmResponse("""
            {"summary":"x","confidence":0.5,"reasoning":"y"}
            """);

        specialist.analyze(sampleInput());

        verify(events, times(1)).llmCall(
            any(SpanContext.class),
            anyString(),
            anyInt(),
            anyInt(),
            any(BigDecimal.class),
            anyLong()
        );
    }

    // ----- helpers -----

    private void stubLlmResponse(String text) {
        when(chat.completeFast(anyString())).thenReturn(
            new ChatClient.ChatCompletion(text, "llama-3.3-70b-versatile", 42, 24, 500L));
    }

    private static SpecialistInput sampleInput() {
        SpanContext parent = SpanContext.root(
            "triage_ABC", Specialist.AGGREGATOR, "orchestrator");
        return new SpecialistInput(
            "triage_ABC",
            "PGR-1234",
            "checkout-service 5xx spike",
            "checkout-api",
            "P2",
            parent
        );
    }
}