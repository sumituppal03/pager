package dev.sumituppal.pager.specialist;

import dev.sumituppal.pager.domain.Specialist;

/**
 * The contract every specialist agent implements.
 *
 * <h2>Why an interface, not a base class?</h2>
 * <p>Specialists differ radically in what they need — Symptoms just looks
 * at the alert; Change needs deploy history; Metrics needs Prometheus.
 * A base class would either be almost empty or accumulate god-object
 * fields. An interface with a single {@code analyze} method keeps each
 * specialist's dependencies explicit in its constructor.
 *
 * <h2>Why {@link SpecialistInput}/{@link SpecialistOutput} records instead
 * of primitive parameters?</h2>
 * <p>Two reasons. First, {@code analyze(triageId, alertSummary, incidentUrl,
 * service, severity, service, sourceEnv, ...)} is unreadable. Records
 * self-document. Second, when we add fields later (correlation window,
 * related_incidents), we don't have to widen every specialist's method
 * signature — we widen the record instead.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #kind()} returns the specialist identity — used for
 *       spans, events, and finding attribution.</li>
 *   <li>{@link #analyze(SpecialistInput)} performs the analysis and
 *       returns exactly one {@link SpecialistOutput}. Never returns
 *       {@code null}; on failure, returns an output with error details
 *       so nothing is silently lost.</li>
 * </ul>
 */
public interface SpecialistAgent {

    /**
     * Which specialist this is. Used in spans and events for
     * correlation and cost accounting.
     */
    Specialist kind();

    /**
     * Analyze the incident and produce exactly one finding.
     *
     * <p>Never returns null. On failure (LLM error, malformed response,
     * parse failure), returns a {@link SpecialistOutput} with
     * {@code category=UNKNOWN} and diagnostic details in {@code payload}.
     * The upstream orchestrator persists this as a Finding either way —
     * so failures are queryable in the DB, not lost.
     */
    SpecialistOutput analyze(SpecialistInput input);
}