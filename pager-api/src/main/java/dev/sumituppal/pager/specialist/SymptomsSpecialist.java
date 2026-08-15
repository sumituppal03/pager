package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The Symptoms specialist — describes what's observably broken.
 *
 * <p>Scoped to observation only. Does NOT identify root causes — that's
 * the Change and Metrics specialists' job. Does NOT categorize into a
 * cause category — that's the Aggregator's job in PR #11. Its {@link
 * SpecialistOutput}s always carry {@link
 * dev.sumituppal.pager.domain.FindingCategory#UNKNOWN}.
 */
@Component
public class SymptomsSpecialist extends AbstractLlmSpecialist {

    public SymptomsSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper,
            dev.sumituppal.pager.rag.HybridRetriever retriever) {
        super(chat, prompts, events, objectMapper, retriever);
    }

    @Override
    public Specialist kind() {
        return Specialist.SYMPTOMS;
    }

    @Override
    protected String promptName() {
        return "symptoms";
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
}