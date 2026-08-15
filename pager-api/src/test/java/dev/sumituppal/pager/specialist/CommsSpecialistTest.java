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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommsSpecialist}.
 *
 * <p>Focus areas: identity, prompt-name wiring, and the key contract
 * this specialist has that others don't — it uses the QUALITY model,
 * not the FAST one.
 *
 * <p>The retriever is mocked with empty results — RAG integration is
 * exercised end-to-end via the manual demo rather than in unit tests.
 */
class CommsSpecialistTest {

    private ChatClient chat;
    private PromptRegistry prompts;
    private AgentEventEmitter events;
    private ObjectMapper objectMapper;
    private HybridRetriever retriever;
    private CommsSpecialist specialist;

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class);
        prompts = mock(PromptRegistry.class);
        events = mock(AgentEventEmitter.class);
        objectMapper = new ObjectMapper();
        retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        when(prompts.get("comms")).thenReturn(
            new PromptTemplate("comms", "v1", "Draft: {{alertSummary}} {{retrievedContext}}"));

        specialist = new CommsSpecialist(chat, prompts, events, objectMapper, retriever);
    }

    @Test
    @DisplayName("kind() reports Specialist.COMMS")
    void kindIsComms() {
        assertThat(specialist.kind()).isEqualTo(Specialist.COMMS);
    }

    @Test
    @DisplayName("uses the QUALITY model, not the FAST model")
    void usesQualityModel() {
        when(chat.completeQuality(anyString())).thenReturn(
            new ChatClient.ChatCompletion(
                "{\"summary\":\"draft\",\"confidence\":0.7,\"reasoning\":\"y\"}",
                "llama-3.3-70b-versatile", 100, 30, 800L));

        specialist.analyze(sampleInput());

        verify(chat, times(1)).completeQuality(anyString());
        verify(chat, never()).completeFast(anyString());
    }

    @Test
    @DisplayName("emits an llm.call event on success")
    void emitsLlmEvent() {
        when(chat.completeQuality(anyString())).thenReturn(
            new ChatClient.ChatCompletion(
                "{\"summary\":\"draft\",\"confidence\":0.7,\"reasoning\":\"y\"}",
                "llama-3.3-70b-versatile", 100, 30, 800L));

        specialist.analyze(sampleInput());

        verify(events, times(1)).llmCall(
            org.mockito.ArgumentMatchers.any(SpanContext.class),
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong()
        );
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