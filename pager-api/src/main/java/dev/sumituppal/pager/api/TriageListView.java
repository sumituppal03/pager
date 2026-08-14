package dev.sumituppal.pager.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Response shape for the triage list endpoint.
 *
 * <p>Deliberately narrow — includes what the list UI needs to render one
 * row and nothing more. The idempotency key, raw webhook payload, and
 * finding details are all in the detail view instead.
 *
 * <h2>Why a record and not a class?</h2>
 * <p>Records auto-generate accessors and are serialized correctly by
 * Jackson without extra annotations. For a pure data-transfer object,
 * they're the ideal Java 21 shape.
 *
 * <h2>Why not just return the {@code TriageRun} entity?</h2>
 * <p>Serializing JPA entities directly leaks internal fields (like
 * {@code idempotencyKey}) and creates a tight coupling between the DB
 * schema and the API contract. Changing a column then breaks all
 * frontend clients. DTOs give the API a stable shape independent of
 * persistence.
 */
public record TriageListView(
    String id,
    String incidentId,
    String alertSummary,
    String service,
    String severity,
    String status,
    String aggregatedCategory,    // from the AGGREGATOR finding
    BigDecimal aggregatedConfidence,
    String aggregatedSummary,
    String notificationDecision,  // AUTO_POSTED / AWAITING_REVIEW / SUPPRESSED / null
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {}