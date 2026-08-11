package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.llm.PromptTemplate;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import dev.sumituppal.pager.observability.Span;
import dev.sumituppal.pager.observability.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * The first specialist — analyzes the alert itself and produces a
 * "what's observably broken" summary.
 *
 * <h2>Scope</h2>
 * <p>Symptoms is deliberately narrow: it only looks at the incident
 * alert and describes what's broken. It does NOT attempt to identify
 * root causes — that's the job of the Change, Metrics, and Aggregator
 * specialists in later PRs.
 *
 * <p>Because this specialist produces observations rather than causes,
 * the {@link FindingCategory} on its output is always {@link
 * FindingCategory#UNKNOWN}. The Aggregator (PR #11) is responsible for
 * mapping observations + other findings to a specific cause category
 * like {@link FindingCategory#DEPLOY_REGRESSION}.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Load the versioned prompt from {@link PromptRegistry}.</li>
 *   <li>Render it with alert details.</li>
 *   <li>Open a child span off the parent orchestrator span.</li>
 *   <li>Call the fast model via {@link ChatClient}.</li>
 *   <li>Emit an {@code llm.call} event with cost + token accounting.</li>
 *   <li>Parse the JSON response — LLMs sometimes wrap output in markdown
 *       code fences, so we strip those defensively.</li>
 *   <li>Return a {@link SpecialistOutput}.</li>
 * </ol>
 *
 * <h2>Never returns null</h2>
 * <p>Every failure mode — LLM timeout, malformed JSON, missing fields —
 * returns {@link SpecialistOutput#unknown(String)} instead of throwing.
 * The orchestrator persists whatever we return, so failures are
 * queryable rather than lost.
 */
@Component
public class SymptomsSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(SymptomsSpecialist.class);
    private static final String PROMPT_NAME = "symptoms";

    private final ChatClient chat;
    private final PromptRegistry prompts;
    private final AgentEventEmitter events;
    private final ObjectMapper objectMapper;

    public SymptomsSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper) {
        this.chat = chat;
        this.prompts = prompts;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Override
    public Specialist kind() {
        return Specialist.SYMPTOMS;
    }

    @Override
    public SpecialistOutput analyze(SpecialistInput input) {
        SpanContext childSpan = input.parentSpan().child(
                Specialist.SYMPTOMS, "specialist.symptoms");

        try (Span span = Span.open(events, childSpan)) {
            try {
                PromptTemplate template = prompts.get(PROMPT_NAME);
                String rendered = template.render(Map.of(
                    "alertSummary", nullToEmpty(input.alertSummary()),
                    "service", nullToEmpty(input.service()),
                    "severity", nullToEmpty(input.severity()),
                    "incidentId", nullToEmpty(input.incidentId())
                ));

                ChatClient.ChatCompletion completion = chat.completeFast(rendered);

                // Emit an llm.call event so cost + latency are queryable
                // in agent_events. Groq's free tier is $0 — we still emit
                // the row for consistency; the cost calculation will move
                // to a proper cost table in a later PR.
                events.llmCall(
                    childSpan,
                    completion.model(),
                    completion.tokensIn(),
                    completion.tokensOut(),
                    BigDecimal.ZERO,
                    completion.latencyMs()
                );

                SpecialistOutput output = parseResponse(completion.text());
                span.setOutcome("completed");
                return output;

            } catch (RuntimeException e) {
                log.warn("symptoms specialist failed for triage {}: {}",
                    input.triageId(), e.getMessage());
                span.recordError(e);
                return SpecialistOutput.unknown(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Parse the LLM's response into a structured output.
     *
     * <p>Models sometimes wrap JSON in Markdown code fences
     * ({@code ```json ... ```}) despite instructions not to.
     * We strip those defensively before parsing.
     *
     * <p>Note: the Symptoms specialist always uses {@link
     * FindingCategory#UNKNOWN} — cause categorization is the
     * Aggregator's job, not this specialist's.
     */
    SpecialistOutput parseResponse(String responseText) {
        String cleaned = stripCodeFences(responseText).trim();

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String summary = node.path("summary").asText("");
            double confidenceRaw = node.path("confidence").asDouble(0.0);
            BigDecimal confidence = BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, confidenceRaw)))
                .setScale(3, RoundingMode.HALF_UP);
            String reasoning = node.path("reasoning").asText("");

            // Store the reasoning + raw response in the payload for audit.
            String payload = objectMapper.writeValueAsString(Map.of(
                "reasoning", reasoning,
                "raw_response", responseText
            ));

            // Symptoms produces observations, not causes.
            // The category stays UNKNOWN — the Aggregator will map
            // observations + other findings to a cause later.
            return new SpecialistOutput(FindingCategory.UNKNOWN, summary, confidence, payload);
        } catch (Exception e) {
            log.warn("failed to parse symptoms response: {}", e.getMessage());
            return SpecialistOutput.unknown(
                "response was not valid JSON: " + e.getMessage());
        }
    }

    private static String stripCodeFences(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring("```json".length());
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}