package dev.sumituppal.pager.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.llm.ChatClient;
import dev.sumituppal.pager.llm.PromptRegistry;
import dev.sumituppal.pager.observability.AgentEventEmitter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The Metrics specialist — hypothesizes what upstream signals (traffic,
 * latency, saturation) might explain the incident.
 *
 * <h2>Current state — no real tools yet</h2>
 * <p>In the final architecture, this specialist queries Prometheus /
 * CloudWatch / Datadog to fetch the actual metric time-series in the
 * incident window and reason about them. For now, the LLM runs against
 * the alert alone and hypothesizes what metric shape would explain the
 * observed behavior.
 *
 * <p>PR #14 will wire this to a real Prometheus tool. The specialist's
 * public contract does not change.
 */
@Component
public class MetricsSpecialist extends AbstractLlmSpecialist {

    public MetricsSpecialist(
            ChatClient chat,
            PromptRegistry prompts,
            AgentEventEmitter events,
            ObjectMapper objectMapper) {
        super(chat, prompts, events, objectMapper);
    }

    @Override
    public Specialist kind() {
        return Specialist.METRICS;
    }

    @Override
    protected String promptName() {
        return "metrics";
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