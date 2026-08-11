package dev.sumituppal.pager.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.sumituppal.pager.config.PagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * LangChain4j-backed implementation of {@link ChatClient}.
 *
 * <h2>Why lazy-init the models?</h2>
 * <p>{@link OpenAiChatModel#builder()} does not itself make network
 * calls, but constructing two of them at bean-creation time when the
 * API key is blank (which is legitimate in tests and in dev before
 * env vars are set) would be wasteful. Lazy-init means:
 * <ul>
 *   <li>App boots fine without an API key set.</li>
 *   <li>The client fails <em>at first use</em> with a clear message,
 *       not at boot with an obscure LangChain4j stack trace.</li>
 * </ul>
 *
 * <h2>Why OpenAiChatModel for Groq?</h2>
 * <p>Groq exposes an OpenAI-compatible API at
 * {@code https://api.groq.com/openai/v1}. Every OpenAI request format
 * works unchanged — just point {@code baseUrl} at Groq and set the
 * appropriate model name. This is the same pattern used for Together
 * AI, DeepInfra, Fireworks, and self-hosted vLLM instances.
 *
 * <h2>Thread safety</h2>
 * <p>{@link OpenAiChatModel} is thread-safe by design (the LangChain4j
 * docs guarantee this). One instance shared across all specialist
 * threads is correct.
 */
@Component
public class ChatClientImpl implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClientImpl.class);

    private final LlmProperties llm;
    private final PagerProperties pager;

    // Lazily-initialized on first use, then reused for the lifetime of
    // the bean. `volatile` is defensive — Spring's proxying probably
    // makes it unnecessary but the cost is nil.
    private volatile ChatLanguageModel fastModel;
    private volatile ChatLanguageModel qualityModel;

    public ChatClientImpl(LlmProperties llm, PagerProperties pager) {
        this.llm = llm;
        this.pager = pager;
    }

    @Override
    public ChatCompletion completeFast(String prompt) {
        return complete(prompt, pager.models().fast(), fastModel());
    }

    @Override
    public ChatCompletion completeQuality(String prompt) {
        return complete(prompt, pager.models().quality(), qualityModel());
    }

    // ---- internals ----

    private ChatCompletion complete(String prompt, String modelName, ChatLanguageModel model) {
        if (llm.apiKey().isBlank()) {
            throw new IllegalStateException(
                "LLM API key is not configured — set PAGER_GROQ_API_KEY " +
                "before making LLM calls");
        }
        long t0 = System.nanoTime();
        try {
            Response<AiMessage> response = model.generate(
                List.of(UserMessage.from(prompt)));
            long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

            int tokensIn = response.tokenUsage() != null
                ? response.tokenUsage().inputTokenCount() : 0;
            int tokensOut = response.tokenUsage() != null
                ? response.tokenUsage().outputTokenCount() : 0;

            log.debug("LLM call completed model={} in={} out={} latencyMs={}",
                modelName, tokensIn, tokensOut, latencyMs);

            return new ChatCompletion(
                response.content().text(),
                modelName,
                tokensIn,
                tokensOut,
                latencyMs
            );
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
            log.warn("LLM call failed model={} latencyMs={} error={}",
                modelName, latencyMs, e.getMessage());
            throw e;
        }
    }

    private ChatLanguageModel fastModel() {
        ChatLanguageModel m = fastModel;
        if (m == null) {
            synchronized (this) {
                if (fastModel == null) {
                    fastModel = buildModel(pager.models().fast());
                }
                m = fastModel;
            }
        }
        return m;
    }

    private ChatLanguageModel qualityModel() {
        ChatLanguageModel m = qualityModel;
        if (m == null) {
            synchronized (this) {
                if (qualityModel == null) {
                    qualityModel = buildModel(pager.models().quality());
                }
                m = qualityModel;
            }
        }
        return m;
    }

    private ChatLanguageModel buildModel(String modelName) {
        return OpenAiChatModel.builder()
            .apiKey(llm.apiKey())
            .baseUrl(llm.baseUrl())
            .modelName(modelName)
            .temperature(llm.temperature())
            .timeout(Duration.ofMillis(llm.timeoutMs()))
            .maxTokens(2048)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}