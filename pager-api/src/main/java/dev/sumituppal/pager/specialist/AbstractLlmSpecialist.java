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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Base class for LLM-backed specialists. Encapsulates the pattern shared
 * by every specialist: open a child span, render the prompt, call the
 * fast LLM, emit an llm.call event, parse the JSON response, return a
 * {@link SpecialistOutput}. Subclasses provide only their identity, prompt
 * name, and prompt variable map.
 *
 * <h2>Why extract a base class after PR #9?</h2>
 * <p>PR #9 landed a standalone {@code SymptomsSpecialist} with the whole
 * pattern inline. Adding Change and Metrics as copy-paste would give us
 * three copies of the same span/LLM/parse/error scaffolding — a real
 * maintenance liability. Extracting shared infrastructure NOW, when we
 * have two more specialists to write, is the right moment.
 *
 * <p>{@code SymptomsSpecialist} is refactored to extend this class as
 * part of this PR — the runtime behavior stays identical.
 *
 * <h2>Contract for subclasses</h2>
 * <ul>
 *   <li>{@link #kind()} returns the specialist identity.</li>
 *   <li>{@link #promptName()} returns the prompt template name (e.g. "symptoms").</li>
 *   <li>{@link #promptVariables(SpecialistInput)} returns the map of variables
 *       to substitute into the prompt. Must include every {@code {{var}}}
 *       the template references — {@link PromptTemplate#render} throws
 *       otherwise, which is caught here and turned into an UNKNOWN output.</li>
 * </ul>
 *
 * <h2>Guaranteed non-throwing</h2>
 * <p>{@code analyze} never throws. Every failure mode (LLM error, missing
 * variable, malformed JSON) returns {@link SpecialistOutput#unknown(String)}.
 * The orchestrator persists whatever we return — failures are queryable
 * rather than lost.
 */
public abstract class AbstractLlmSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmSpecialist.class);

    protected final ChatClient chat;
    protected final PromptRegistry prompts;
    protected final AgentEventEmitter events;
    protected final ObjectMapper objectMapper;

    protected AbstractLlmSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper) {
        this.chat = chat;
        this.prompts = prompts;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** Prompt template name for this specialist — e.g. "symptoms", "change". */
    protected abstract String promptName();

    /** Variables to substitute into the prompt. */
    protected abstract Map<String, String> promptVariables(SpecialistInput input);

    @Override
    public SpecialistOutput analyze(SpecialistInput input) {
        SpanContext childSpan = input.parentSpan().child(
                kind(), "specialist." + kind().name().toLowerCase());

        try (Span span = Span.open(events, childSpan)) {
            try {
                PromptTemplate template = prompts.get(promptName());
                String rendered = template.render(promptVariables(input));

                ChatClient.ChatCompletion completion = chat.completeFast(rendered);

                // Cost is $0 for Groq's free tier; we still emit the event
                // for consistency. A proper cost calculation moves to a
                // ModelPricing service in a later PR.
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
                log.warn("{} specialist failed for triage {}: {}",
                    kind().name().toLowerCase(), input.triageId(), e.getMessage());
                span.recordError(e);
                return SpecialistOutput.unknown(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Parse the LLM's response into a structured output.
     *
     * <p>Overridable so a specialist can extract additional fields
     * beyond the base envelope. Default implementation extracts
     * summary, confidence, and reasoning; category is always UNKNOWN
     * (Aggregator maps observations to causes later).
     */
    SpecialistOutput parseResponse(String responseText) {
        String cleaned = extractFirstJsonObject(stripCodeFences(responseText)).trim();

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String summary = node.path("summary").asText("");
            double confidenceRaw = node.path("confidence").asDouble(0.0);
            BigDecimal confidence = BigDecimal
                .valueOf(Math.max(0.0, Math.min(1.0, confidenceRaw)))
                .setScale(3, RoundingMode.HALF_UP);
            String reasoning = node.path("reasoning").asText("");

            String payload = objectMapper.writeValueAsString(Map.of(
                "reasoning", reasoning,
                "raw_response", responseText
            ));

            return new SpecialistOutput(
                FindingCategory.UNKNOWN, summary, confidence, payload);
        } catch (Exception e) {
            log.warn("{} failed to parse response: {}",
                kind().name().toLowerCase(), e.getMessage());
            // Preserve the raw LLM response even on parse failure so we
            // can diagnose exactly what came back. This was a real
            // debugging blocker discovered while shipping PR #10.
            String payload;
            try {
                payload = objectMapper.writeValueAsString(Map.of(
                    "error", "response was not valid JSON: " + e.getMessage(),
                    "raw_response", responseText != null ? responseText : ""
                ));
            } catch (Exception ex) {
                payload = "{\"error\":\"payload serialization failed\"}";
            }
            return new SpecialistOutput(
                FindingCategory.UNKNOWN,
                "specialist could not produce a finding",
                BigDecimal.ZERO,
                payload
            );
        }
    }

    /**
     * LLMs sometimes wrap JSON in Markdown code fences despite instructions
     * to the contrary. Strip them defensively before parsing.
     */
    protected static String stripCodeFences(String s) {
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
    
    protected static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        // No balanced object found — return original for caller to handle
        return s;
    }

    protected static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @SuppressWarnings("unused")
    private Specialist kindHint() {
        // Silences unused-import warnings in tooling. No runtime effect.
        return null;
    }
}