package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The Comms specialist — drafts the user-facing Slack summary.
 *
 * <h2>What makes Comms different</h2>
 * <p>Symptoms, Change, and Metrics each produce a technical hypothesis
 * consumed by other machinery (the Aggregator). Comms produces the
 * <em>artifact humans read at 3 AM</em>. Its output is graded on
 * clarity, actionability, and tone — not classification accuracy.
 *
 * <h2>Why Comms overrides {@code chatFor} to use the quality model</h2>
 * <p>The base {@link AbstractLlmSpecialist} calls
 * {@link ChatClient#completeFast(String)}. Comms overrides this to call
 * {@link ChatClient#completeQuality(String)} instead — a marginally
 * more expensive model call for text that a human will actually read.
 *
 * <p>In interviews: "Model routing by cost/quality — cheap classifiers
 * for triage, expensive models for user-facing prose."
 */
@Component
public class CommsSpecialist extends AbstractLlmSpecialist {

    public CommsSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper) {
        super(chat, prompts, events, objectMapper);
    }

    @Override
    public Specialist kind() {
        return Specialist.COMMS;
    }

    @Override
    protected String promptName() {
        return "comms";
    }

    @Override
    protected Map<String, String> promptVariables(SpecialistInput input) {
        return Map.of(
            "alertSummary", nullToEmpty(input.alertSummary()),
            "service", nullToEmpty(input.service()),
            "severity", nullToEmpty(input.severity()),
            "incidentId", nullToEmpty(input.incidentId())
        );
    }

    /**
     * Route this specialist's completions through the quality model.
     *
     * <p>The base class's {@code analyze} calls {@code completeFast}
     * directly. To avoid changing that method's shape (and rippling
     * changes across the three other specialists), we override the LLM
     * call path here with a template-method hook.
     */
    @Override
    protected ChatClient.ChatCompletion callLlm(String rendered) {
        return chat.completeQuality(rendered);
    }
}