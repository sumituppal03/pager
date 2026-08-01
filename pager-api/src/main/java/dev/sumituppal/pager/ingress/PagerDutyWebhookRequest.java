package dev.sumituppal.pager.ingress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The PagerDuty webhook payload shape we care about.
 *
 * <p>PagerDuty sends a huge JSON object with dozens of fields, most of
 * which we don't need. {@link JsonIgnoreProperties#ignoreUnknown()} means
 * we tolerate future PagerDuty additions without breaking parsing —
 * critical for a webhook receiver that we don't control the sender of.
 *
 * <p>Shape (simplified from PagerDuty's docs):
 * <pre>
 *   {
 *     "event": {
 *       "id": "abc-123-uuid",
 *       "event_type": "incident.triggered",
 *       "data": {
 *         "id": "PGRXXXX",
 *         "title": "Checkout service 5xx spike",
 *         "urgency": "high",
 *         "html_url": "https://acme.pagerduty.com/incidents/PGRXXXX",
 *         "service": { "id": "PS12345", "summary": "checkout-api" }
 *       }
 *     }
 *   }
 * </pre>
 *
 * <p>We normalise this into a Triage in the service layer; the controller
 * itself does no interpretation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PagerDutyWebhookRequest(
        @JsonProperty("event") Event event
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(
            @JsonProperty("id") String id,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("data") Data data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("urgency") String urgency,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("service") Service service
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Service(
            @JsonProperty("id") String id,
            @JsonProperty("summary") String summary
    ) {}

    /**
     * Map PagerDuty's "urgency" string to our internal Severity ladder.
     * PD only distinguishes "high" vs "low" — we take the pragmatic mapping.
     */
    public String derivedSeverity() {
        if (event == null || event.data == null) return "P3";
        return switch (event.data.urgency == null ? "" : event.data.urgency) {
            case "high" -> "P1";
            case "low"  -> "P3";
            default     -> "P3";
        };
    }

    public String incidentId() {
        return event != null && event.data != null ? event.data.id : null;
    }

    public String alertSummary() {
        return event != null && event.data != null ? event.data.title : null;
    }

    public String serviceName() {
        return event != null && event.data != null && event.data.service != null
                ? event.data.service.summary
                : null;
    }

    public String incidentUrl() {
        return event != null && event.data != null ? event.data.htmlUrl : null;
    }
}