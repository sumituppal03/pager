package dev.sumituppal.pager.llm;

/**
 * Thin domain interface for LLM chat completions.
 *
 * <h2>Why wrap LangChain4j at all?</h2>
 * <p>LangChain4j is a fast-moving library — types have moved between
 * packages, breaking changes happen at minor-version bumps, and the
 * project could genuinely lose momentum in the next 12 months as Spring
 * AI matures. Wrapping it behind {@code ChatClient} means every
 * specialist depends on this one file, not on {@code dev.langchain4j.*}
 * imports scattered across the codebase.
 *
 * <h2>Why is this an interface, not a class?</h2>
 * <p>Testing. Every specialist that calls the LLM gets easy mocking of
 * this interface. The concrete {@link ChatClientImpl} that wires up
 * LangChain4j is one file that also gets its own tests.
 *
 * <h2>Why "fast" vs "quality" instead of a model string?</h2>
 * <p>Model routing is a configuration concern, not a caller concern.
 * A specialist that needs "cheap classification" shouldn't have to know
 * that today it's llama-3.3-70b via Groq and tomorrow might be Claude
 * Haiku or gpt-4o-mini. Encoding the intent ("fast", "quality") in the
 * method name lets us swap models via yaml without touching any callers.
 */
public interface ChatClient {

    /**
     * Complete a chat using the "fast" model — used by specialists that
     * do classification, extraction, or short structured output.
     *
     * @param prompt fully-rendered prompt text
     * @return the model's completion text
     */
    ChatCompletion completeFast(String prompt);

    /**
     * Complete a chat using the "quality" model — used by the comms
     * specialist for user-facing summaries where output quality matters
     * more than latency or cost.
     *
     * @param prompt fully-rendered prompt text
     * @return the model's completion text
     */
    ChatCompletion completeQuality(String prompt);

    /**
     * The result of one chat completion.
     *
     * <p>Carries the completion text plus token/cost/latency accounting
     * that will be written to {@code agent_events} by callers via
     * {@link dev.sumituppal.pager.observability.AgentEventEmitter#llmCall}.
     *
     * @param text        the model's response text
     * @param model       the specific model that produced this response
     *                    (e.g. {@code llama-3.3-70b-versatile})
     * @param tokensIn    prompt tokens consumed
     * @param tokensOut   completion tokens produced
     * @param latencyMs   wall-clock time for the call
     */
    record ChatCompletion(
        String text,
        String model,
        int tokensIn,
        int tokensOut,
        long latencyMs
    ) {}
}