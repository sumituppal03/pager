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
import java.util.List;
import java.util.Map;

/**
 * Synthesizes multiple specialist findings into one merged conclusion.
 *
 * <h2>Not a Specialist</h2>
 * <p>Aggregator does not extend {@link AbstractLlmSpecialist} because
 * its input shape is different: it doesn't analyze the alert, it
 * analyzes the <em>specialists' analyses</em>. Its own output is a
 * {@link SpecialistOutput} for structural consistency (so the
 * orchestrator can persist it via the same {@code persistFinding}
 * path), but its category is a real {@link FindingCategory} value —
 * a cause, not UNKNOWN — because it has enough evidence to categorize.
 *
 * <h2>Why quality model, not fast?</h2>
 * <p>Same reason as {@link CommsSpecialist}: this is user-facing text
 * that needs to read well. The extra latency is worth it.
 *
 * <h2>What happens when specialists disagree?</h2>
 * <p>Nothing structural. The LLM sees all four findings — including
 * their confidence values and reasoning — and decides how to weigh
 * them in its merged summary. Disagreement shows up as lower reported
 * confidence in the aggregator's own output. A future PR could add
 * an explicit "cross-specialist agreement score" computed
 * deterministically before the LLM call, but for now the model's
 * judgment is good enough.
 *
 * <h2>Failure isolation</h2>
 * <p>Never throws. On any LLM/parse failure, returns a
 * {@link SpecialistOutput} with the highest-confidence input finding's
 * summary as a fallback (better than nothing) and category UNKNOWN.
 */
@Component
public class Aggregator {

    private static final Logger log = LoggerFactory.getLogger(Aggregator.class);
    private static final String PROMPT_NAME = "aggregator";

    private final ChatClient chat;
    private final PromptRegistry prompts;
    private final AgentEventEmitter events;
    private final ObjectMapper objectMapper;

    public Aggregator(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper) {
        this.chat = chat;
        this.prompts = prompts;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Merge the specialists' findings into one conclusion.
     *
     * @param triageId     the enclosing triage
     * @param parentSpan   the orchestrator's span (aggregator opens a child)
     * @param inputs       one entry per specialist finding, in stable order
     * @return one merged output with a real {@link FindingCategory} and
     *         a Slack-ready summary
     */
    public SpecialistOutput aggregate(
            String triageId,
            SpanContext parentSpan,
            List<SpecialistFinding> inputs) {

        SpanContext childSpan = parentSpan.child(
                Specialist.AGGREGATOR, "aggregator");

        try (Span span = Span.open(events, childSpan)) {
            try {
                PromptTemplate template = prompts.get(PROMPT_NAME);
                String rendered = template.render(Map.of(
                    "findingsJson", serializeFindings(inputs)
                ));

                ChatClient.ChatCompletion completion = chat.completeQuality(rendered);

                events.llmCall(
                    childSpan,
                    completion.model(),
                    completion.tokensIn(),
                    completion.tokensOut(),
                    BigDecimal.ZERO,
                    completion.latencyMs()
                );

                SpecialistOutput output = parseResponse(completion.text(), inputs);
                span.setOutcome("completed");
                return output;

            } catch (RuntimeException e) {
                log.warn("aggregator failed for triage {}: {}",
                    triageId, e.getMessage());
                span.recordError(e);
                return fallback(inputs, e.getMessage());
            }
        }
    }

    private String serializeFindings(List<SpecialistFinding> inputs) {
        try {
            return objectMapper.writeValueAsString(inputs);
        } catch (Exception e) {
            // Fall back to a minimal representation.
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < inputs.size(); i++) {
                SpecialistFinding f = inputs.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format(
                    "{\"specialist\":\"%s\",\"summary\":\"%s\",\"confidence\":%s}",
                    f.specialist(), f.summary().replace("\"", "\\\""), f.confidence()));
            }
            return sb.append("]").toString();
        }
    }

    private SpecialistOutput parseResponse(String responseText, List<SpecialistFinding> inputs) {
        String cleaned = extractFirstJsonObject(stripCodeFences(responseText)).trim();

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String summary = node.path("summary").asText("");
            String categoryStr = node.path("category").asText("UNKNOWN");
            double confidenceRaw = node.path("confidence").asDouble(0.0);
            BigDecimal confidence = BigDecimal
                .valueOf(Math.max(0.0, Math.min(1.0, confidenceRaw)))
                .setScale(3, RoundingMode.HALF_UP);
            String reasoning = node.path("reasoning").asText("");

            FindingCategory category = parseCategory(categoryStr);

            String payload = objectMapper.writeValueAsString(Map.of(
                "reasoning", reasoning,
                "raw_response", responseText,
                "specialist_findings", inputs
            ));

            return new SpecialistOutput(category, summary, confidence, payload);

        } catch (Exception e) {
            log.warn("aggregator failed to parse response: {}", e.getMessage());
            return fallback(inputs, "response was not valid JSON: " + e.getMessage());
        }
    }

    /**
     * Fallback: pick the highest-confidence input finding's summary.
     * Better than losing the triage's summary entirely; the raw error
     * is preserved in the payload for debugging.
     */
    private SpecialistOutput fallback(List<SpecialistFinding> inputs, String errorMessage) {
        SpecialistFinding best = inputs.stream()
            .max((a, b) -> a.confidence().compareTo(b.confidence()))
            .orElse(null);

        String summary = (best != null && !best.summary().isBlank())
            ? best.summary()
            : "No specialist produced a usable finding.";

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "error", "aggregator fell back: " + errorMessage,
                "specialist_findings", inputs
            ));
        } catch (Exception ex) {
            payload = "{\"error\":\"aggregator payload serialization failed\"}";
        }

        return new SpecialistOutput(
            FindingCategory.UNKNOWN,
            summary,
            BigDecimal.ZERO,
            payload
        );
    }

    private static FindingCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return FindingCategory.UNKNOWN;
        try {
            return FindingCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Try dbValue lookup as a fallback (LLM might return
            // "deploy_regression" instead of "DEPLOY_REGRESSION").
            try {
                return FindingCategory.fromDbValue(raw.trim().toLowerCase());
            } catch (IllegalArgumentException e2) {
                return FindingCategory.UNKNOWN;
            }
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

    private static String extractFirstJsonObject(String s) {
        if (s == null) return "";
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString && c == '\\' && i + 1 < s.length()) {
                i++;
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
        return s;
    }

    /**
     * Compact input shape for a single specialist's finding. Used only
     * as input to the aggregator, not for persistence.
     */
    public record SpecialistFinding(
        String specialist,
        String summary,
        BigDecimal confidence,
        String reasoning
    ) {}
}