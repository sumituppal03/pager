package dev.sumituppal.pager.llm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev-only endpoint to smoke-test the LLM pipeline end-to-end.
 *
 * <p>Sends a rendered prompt from the registry through the fast model
 * and returns the response. Useful for verifying that the API key,
 * network, LangChain4j wiring, and model name are all correct before
 * relying on the pipeline from within specialists.
 *
 * <p>In a later PR we'll gate this behind an environment / profile
 * check so it doesn't ship to prod. For now, the endpoint being open
 * is fine — no state changes, no expensive work, no PII exposure.
 *
 * <p>Usage: {@code curl "http://localhost:8080/dev/llm/health?alert=checkout+5xx"}
 */
@RestController
@RequestMapping("/dev/llm")
public class LlmHealthController {

    private final ChatClient chat;
    private final PromptRegistry prompts;

    public LlmHealthController(ChatClient chat, PromptRegistry prompts) {
        this.chat = chat;
        this.prompts = prompts;
    }

    @GetMapping("/health")
    public Map<String, Object> health(
            @RequestParam(defaultValue = "test alert — pipeline check") String alert) {
        PromptTemplate template = prompts.get("health-check");
        String rendered = template.render(Map.of("alertSummary", alert));

        ChatClient.ChatCompletion result = chat.completeFast(rendered);

        return Map.of(
            "prompt_id", template.id(),
            "model", result.model(),
            "response", result.text(),
            "tokens_in", result.tokensIn(),
            "tokens_out", result.tokensOut(),
            "latency_ms", result.latencyMs()
        );
    }
}