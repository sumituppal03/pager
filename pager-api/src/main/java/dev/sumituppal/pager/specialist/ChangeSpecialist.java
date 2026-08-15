package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The Change specialist — hypothesizes whether recent deploys or config
 * changes could explain the incident.
 *
 * <h2>Current state — no real tools yet</h2>
 * <p>In the final architecture, this specialist queries a deploy-history
 * tool (GitHub Actions, ArgoCD, Spinnaker) to enumerate deploys inside
 * the incident window and reason about them. For now, we run the LLM
 * against the alert alone and ask it to hypothesize whether the alert
 * <em>shape</em> is consistent with a deploy regression.
 *
 * <p>PR #14 will wire this to a real (or stubbed) deploy-history tool.
 * The specialist's public contract does not change — only its prompt
 * variables + template will grow.
 */
@Component
public class ChangeSpecialist extends AbstractLlmSpecialist {

    public ChangeSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper,
            dev.sumituppal.pager.rag.HybridRetriever retriever) {
        super(chat, prompts, events, objectMapper, retriever);
    }

    @Override
    public Specialist kind() {
        return Specialist.CHANGE;
    }

    @Override
    protected String promptName() {
        return "change";
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