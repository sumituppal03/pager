package dev.sumituppal.pager.observability;

import dev.sumituppal.pager.domain.Specialist;

/**
 * The identity + hierarchy of one span.
 *
 * <p>A span is a bounded unit of work — the orchestrator run, one specialist
 * evaluation, one LLM call, one tool call. Every event we emit belongs to
 * exactly one span. This record carries the identifiers we need to correlate
 * events into a coherent trace.
 *
 * <h2>Why an immutable record?</h2>
 * <p>Spans are passed across methods (into helpers, into future async
 * boundaries). A mutable span object would risk one caller accidentally
 * changing another's state. Records give us value semantics for free —
 * pass a {@code SpanContext} anywhere and it's just data.
 *
 * <h2>Why nullable parentSpanId?</h2>
 * <p>The root span of a triage has no parent. Every span inside has one.
 * We generate span IDs on span creation, not from any thread-local — so
 * multi-threaded fan-out (planned for PR #11 with parallel specialists)
 * will work correctly without race conditions.
 *
 * @param triageId      the enclosing triage — used to filter events per triage
 * @param spanId        this span's unique ID (nanoid, generated at start)
 * @param parentSpanId  the enclosing span's ID; null for the root span
 * @param specialist    which specialist this span belongs to (or AGGREGATOR
 *                      for orchestrator-level spans)
 * @param name          the human-readable name of what this span represents
 *                      (e.g. "orchestrator", "specialist.symptoms", "llm.call")
 */
public record SpanContext(
    String triageId,
    String spanId,
    String parentSpanId,
    Specialist specialist,
    String name
) {
    /**
     * Convenience builder for a root span (no parent).
     */
    public static SpanContext root(String triageId, Specialist specialist, String name) {
        return new SpanContext(
            triageId,
            dev.sumituppal.pager.domain.IdGenerator.generate("span"),
            null,
            specialist,
            name
        );
    }

    /**
     * Convenience builder for a child span nested inside this one.
     * The child shares the triage and inherits this as parent.
     */
    public SpanContext child(Specialist childSpecialist, String childName) {
        return new SpanContext(
            triageId,
            dev.sumituppal.pager.domain.IdGenerator.generate("span"),
            this.spanId,
            childSpecialist,
            childName
        );
    }
}