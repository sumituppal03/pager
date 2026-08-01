package dev.sumituppal.pager.ingress;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The message we place on the Redis queue for the worker to pick up.
 *
 * <p>Deliberately small. The worker only needs enough to fetch the full
 * triage record from Postgres — it does <em>not</em> need the full raw
 * payload duplicated on the queue. Postgres is the source of truth;
 * Redis is just a work-ordering signal.
 *
 * <p>Serialized as JSON via the {@code triageQueueTemplate} bean
 * (see {@code RedisConfig}). {@link JsonCreator} + explicit
 * {@link JsonProperty} annotations mean Jackson doesn't need to guess
 * property names — critical because the JVM erases record parameter
 * names without {@code -parameters} at compile time.
 */
public record TriageJob(
        @JsonProperty("triageId") String triageId,
        @JsonProperty("incidentId") String incidentId,
        @JsonProperty("attempt") int attempt
) {

    @JsonCreator
    public TriageJob(
            @JsonProperty("triageId") String triageId,
            @JsonProperty("incidentId") String incidentId,
            @JsonProperty("attempt") int attempt
    ) {
        this.triageId = triageId;
        this.incidentId = incidentId;
        this.attempt = attempt;
    }

    public static TriageJob firstAttempt(String triageId, String incidentId) {
        return new TriageJob(triageId, incidentId, 1);
    }
}