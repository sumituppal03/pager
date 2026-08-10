package dev.sumituppal.pager.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and serves versioned prompt templates from the classpath.
 *
 * <h2>Layout</h2>
 * <p>Templates live under {@code src/main/resources/prompts/} with the
 * filename encoding both name and version: {@code <name>.<version>.md}
 * (e.g. {@code symptoms.v1.md}, {@code comms.v2.md}). The Markdown
 * extension is convention only — the body is plain text as far as the
 * LLM is concerned.
 *
 * <h2>Why eager load at startup?</h2>
 * <p>Two reasons:
 * <ol>
 *   <li>A missing or malformed prompt file should crash the app at
 *       boot, not at first triage. Fail-fast configuration is the same
 *       principle as {@link dev.sumituppal.pager.config.PagerProperties}.</li>
 *   <li>Prompt files are tiny (< 10 KB each). Loading a few dozen at
 *       startup costs nothing and eliminates a class of file-system
 *       races in specialist code.</li>
 * </ol>
 *
 * <h2>Retrieval</h2>
 * <p>Callers request a template by name and get the latest version
 * loaded ({@link #get(String)}), or by explicit name + version
 * ({@link #get(String, String)}). Requesting an unknown template throws
 * — same fail-fast principle.
 */
@Component
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);
    private static final String CLASSPATH_GLOB = "classpath:prompts/*.md";

    /**
     * Filename pattern: {@code <name>.<version>.md}
     * Groups: 1 = name, 2 = version.
     */
    private static final Pattern FILENAME =
        Pattern.compile("^([a-z][a-z0-9-]*)\\.(v\\d+)\\.md$");

    /** name → (version → template). Populated at startup, never mutated after. */
    private final Map<String, Map<String, PromptTemplate>> byNameThenVersion = new HashMap<>();

    /** name → latest version present. */
    private final Map<String, String> latestVersion = new HashMap<>();

    @PostConstruct
    void loadAll() throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(CLASSPATH_GLOB);
        if (resources.length == 0) {
            log.warn("no prompt templates found under prompts/*.md");
            return;
        }
        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename == null) continue;
            Matcher m = FILENAME.matcher(filename);
            if (!m.matches()) {
                throw new IllegalStateException(
                    "prompt filename does not match <name>.<version>.md: " + filename);
            }
            String name = m.group(1);
            String version = m.group(2);
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            PromptTemplate template = new PromptTemplate(name, version, body);

            byNameThenVersion
                .computeIfAbsent(name, k -> new HashMap<>())
                .put(version, template);

            // Track the highest-numbered version we've seen for this name.
            String existing = latestVersion.get(name);
            if (existing == null || compareVersions(version, existing) > 0) {
                latestVersion.put(name, version);
            }
        }
        log.info("loaded {} prompt template(s) across {} name(s): {}",
            resources.length, byNameThenVersion.size(), latestVersion);
    }

    /**
     * Latest-version lookup — what specialists use in production code.
     */
    public PromptTemplate get(String name) {
        String version = latestVersion.get(name);
        if (version == null) {
            throw new IllegalArgumentException("no prompt registered under name: " + name);
        }
        return get(name, version);
    }

    /**
     * Explicit-version lookup — for A/B testing or replay of historical
     * runs against the exact prompt that produced them.
     */
    public PromptTemplate get(String name, String version) {
        Map<String, PromptTemplate> versions = byNameThenVersion.get(name);
        if (versions == null || !versions.containsKey(version)) {
            throw new IllegalArgumentException(
                "no prompt registered for " + name + "." + version);
        }
        return versions.get(version);
    }

    /**
     * Compare "v1" vs "v10" numerically, not lexicographically. Without
     * this, "v10" would sort before "v2" and the registry would report
     * the wrong latest version.
     */
    private static int compareVersions(String a, String b) {
        int na = Integer.parseInt(a.substring(1));
        int nb = Integer.parseInt(b.substring(1));
        return Integer.compare(na, nb);
    }
}