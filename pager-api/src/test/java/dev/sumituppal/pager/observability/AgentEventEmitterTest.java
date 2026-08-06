package dev.sumituppal.pager.observability;

import dev.sumituppal.pager.domain.AgentEvent;
import dev.sumituppal.pager.domain.AgentEventRepository;
import dev.sumituppal.pager.domain.AgentEventType;
import dev.sumituppal.pager.domain.Specialist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link AgentEventEmitter}.
 *
 * <p>These tests verify the wire format: what columns get populated for each
 * event type. The DB round-trip is covered by
 * {@link dev.sumituppal.pager.domain.DomainPersistenceIntegrationTest}.
 */
class AgentEventEmitterTest {

    private AgentEventRepository repository;
    private AgentEventEmitter emitter;
    private SpanContext ctx;

    @BeforeEach
    void setUp() {
        repository = mock(AgentEventRepository.class);
        emitter = new AgentEventEmitter(repository);
        ctx = SpanContext.root("triage_ABC", Specialist.AGGREGATOR, "orchestrator");
    }

    @Test
    @DisplayName("spanStart emits a SPAN_START row with basic fields populated")
    void spanStartBasics() {
        emitter.spanStart(ctx);

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.SPAN_START);
        assertThat(saved.getTriageId()).isEqualTo("triage_ABC");
        assertThat(saved.getSpanId()).isEqualTo(ctx.spanId());
        assertThat(saved.getParentSpanId()).isNull();
        assertThat(saved.specialistEnum()).isEqualTo(Specialist.AGGREGATOR);
        assertThat(saved.getTs()).isNotNull();
    }

    @Test
    @DisplayName("spanEnd emits SPAN_END with latency and outcome")
    void spanEndCarriesLatencyAndOutcome() {
        emitter.spanEnd(ctx, 1234L, "completed");

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.SPAN_END);
        assertThat(saved.getLatencyMs()).isEqualTo(1234);
        assertThat(saved.getOutcome()).isEqualTo("completed");
    }

    @Test
    @DisplayName("llmCall captures model, tokens, cost, latency")
    void llmCallFields() {
        emitter.llmCall(ctx, "gpt-4o-mini", 1024, 128,
            new BigDecimal("0.000234"), 512L);

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.LLM_CALL);
        assertThat(saved.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(saved.getTokensIn()).isEqualTo(1024);
        assertThat(saved.getTokensOut()).isEqualTo(128);
        assertThat(saved.getCostUsd()).isEqualByComparingTo("0.000234");
        assertThat(saved.getLatencyMs()).isEqualTo(512);
    }

    @Test
    @DisplayName("toolCall captures tool name, latency, outcome")
    void toolCallFields() {
        emitter.toolCall(ctx, "prometheus", 45L, "success");

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.TOOL_CALL);
        assertThat(saved.getToolName()).isEqualTo("prometheus");
        assertThat(saved.getLatencyMs()).isEqualTo(45);
        assertThat(saved.getOutcome()).isEqualTo("success");
    }

    @Test
    @DisplayName("decision captures outcome and confidence")
    void decisionFields() {
        emitter.decision(ctx, "auto-post", new BigDecimal("0.870"));

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.DECISION);
        assertThat(saved.getOutcome()).isEqualTo("auto-post");
        assertThat(saved.getConfidence()).isEqualByComparingTo("0.870");
    }

    @Test
    @DisplayName("error captures message inside JSONB payload")
    void errorFieldsPayload() {
        emitter.error(ctx, "prometheus query timed out");

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.ERROR);
        assertThat(saved.getOutcome()).isEqualTo("error");
        assertThat(saved.getPayload()).contains("prometheus query timed out");
    }

    @Test
    @DisplayName("error handles null message without crashing")
    void errorWithNullMessage() {
        emitter.error(ctx, null);

        AgentEvent saved = capture();
        assertThat(saved.eventTypeEnum()).isEqualTo(AgentEventType.ERROR);
        assertThat(saved.getPayload()).isNull();
    }

    @Test
    @DisplayName("repository failure is swallowed — event writes never fail a triage")
    void repositoryFailureIsSwallowed() {
        doThrow(new RuntimeException("simulated DB blip"))
            .when(repository).save(any());

        // Must NOT throw
        emitter.spanStart(ctx);
        emitter.llmCall(ctx, "m", 1, 1, BigDecimal.ZERO, 1);
    }

    // -----

    private AgentEvent capture() {
        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(repository, times(1)).save(captor.capture());
        return captor.getValue();
    }
}