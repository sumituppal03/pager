package dev.sumituppal.pager.specialist;

import dev.sumituppal.pager.observability.SpanContext;

/**
 * Everything a specialist needs to analyze one incident.
 *
 * <h2>Why include the parent span context?</h2>
 * <p>Every specialist opens a child span for its work. To do that, it
 * needs the orchestrator's span context. Passing it through the input
 * makes the specialist testable without any thread-local machinery —
 * a test just constructs an input with a synthetic {@link SpanContext}.
 *
 * <h2>Why not pass the whole {@code TriageRun}?</h2>
 * <p>Coupling. {@code TriageRun} is a JPA entity with lazy loading,
 * lifecycle callbacks, and version fields. Passing it to a specialist
 * means the specialist could mutate it, trigger unexpected queries,
 * or ripple JPA context issues. A flat record of just the fields we
 * need is a clean value boundary.
 *
 * @param triageId       the enclosing triage
 * @param incidentId     the upstream PagerDuty incident id
 * @param alertSummary   the human-readable alert title
 * @param service        the affected service (may be null)
 * @param severity       severity as string ("P1", "P2", ...)
 * @param parentSpan     the orchestrator's span — specialists open child spans off this
 */
public record SpecialistInput(
    String triageId,
    String incidentId,
    String alertSummary,
    String service,
    String severity,
    SpanContext parentSpan
) {}