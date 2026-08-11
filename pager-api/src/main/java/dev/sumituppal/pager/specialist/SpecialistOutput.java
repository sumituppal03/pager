package dev.sumituppal.pager.specialist;

import dev.sumituppal.pager.domain.FindingCategory;

import java.math.BigDecimal;

/**
 * One specialist's conclusion about one incident.
 *
 * <p>Maps almost 1:1 to the {@code findings} table — the orchestrator
 * turns this into a {@code Finding} entity for persistence. Keeping the
 * output shape separate from the entity means the specialist doesn't
 * import JPA types and stays a pure function of its input.
 *
 * <h2>Why {@link BigDecimal} for confidence?</h2>
 * <p>Matches the schema (NUMERIC(4,3)) and gives us predictable rounding.
 * {@code double} loses precision at the 3rd decimal in aggregate
 * calculations — {@code (0.7 + 0.15).equals(0.85)} is false in Java.
 * BigDecimal is the safe choice for anything that participates in
 * arithmetic used to make decisions.
 *
 * @param category    what kind of finding this is
 * @param summary     one-line, human-readable conclusion
 * @param confidence  0.000 to 1.000 — how sure the specialist is
 * @param payload     optional JSON with additional structured detail
 *                    (evidence, sub-scores, raw LLM response for audit)
 */
public record SpecialistOutput(
    FindingCategory category,
    String summary,
    BigDecimal confidence,
    String payload
) {

    /**
     * Convenience factory for the "I couldn't do my job" case — LLM
     * errored, output unparseable, etc. Category UNKNOWN, confidence
     * 0.0, error details in payload.
     */
    public static SpecialistOutput unknown(String reason) {
        return new SpecialistOutput(
            FindingCategory.UNKNOWN,
            "specialist could not produce a finding",
            BigDecimal.ZERO,
            "{\"error\":" + jsonQuote(reason) + "}"
        );
    }

    private static String jsonQuote(String s) {
        // Minimal escaping — only used for error strings under our control.
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}