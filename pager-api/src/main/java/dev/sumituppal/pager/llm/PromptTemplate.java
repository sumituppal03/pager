package dev.sumituppal.pager.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A loaded, versioned prompt template with named-variable substitution.
 *
 * <h2>Why not just use String.format?</h2>
 * <p>Positional format strings ({@code %s}, {@code %d}) are opaque at
 * call sites — you have to check the template to know what each
 * argument means. Named variables ({@code {{alertSummary}}}) let the
 * template read like documentation and give call sites a self-checking
 * map.
 *
 * <h2>Why not full templating like Mustache or Freemarker?</h2>
 * <p>Overkill and it introduces expression injection risk (a runbook
 * containing {@code {{secret}}} shouldn't be evaluated). Named-only
 * substitution — literal {@code {{name}}} to value, no logic — is the
 * safe subset.
 *
 * <h2>Versioning</h2>
 * <p>The version is part of the identity, loaded from the filename
 * (see {@link PromptRegistry}). A prompt file is immutable once used;
 * changing a prompt means creating a new versioned file. This lets us
 * A/B test prompts and correlate outputs to exact prompt versions in
 * {@code agent_events}, which will become essential when we add prompt
 * eval in a later PR.
 *
 * @param name     e.g. "symptoms" — the family of prompts this belongs to
 * @param version  e.g. "v1" — bump on any change to the template body
 * @param body     the template text with {@code {{var}}} placeholders
 */
public record PromptTemplate(String name, String version, String body) {

    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    /**
     * The unique identifier for this template — used for logging and
     * event emission so a prompt's provenance is queryable.
     */
    public String id() {
        return name + "." + version;
    }

    /**
     * Render the template with the given variables. Missing variables
     * throw immediately — silent partial rendering (Mustache's default)
     * has caused too many prod incidents in real LLM systems.
     */
    public String render(Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = vars.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                    "Missing variable '" + key + "' when rendering prompt " + id());
            }
            // Escape any $ or backslash in the value so Matcher.appendReplacement
            // treats them literally. Otherwise a $ in a runbook body would be
            // interpreted as a regex back-reference and blow up.
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}