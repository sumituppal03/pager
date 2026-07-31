package dev.sumituppal.pager.domain;

/**
 * Status of a triage run in its lifecycle.
 *
 * <p>The stored values in the {@code triage_runs.status} column are the
 * lowercase names (matched by the CHECK constraint in V1__initial_schema.sql).
 * We map via {@link #dbValue()} rather than {@code .name().toLowerCase()}
 * to keep the mapping explicit and searchable.
 */
public enum TriageStatus {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String dbValue;

    TriageStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TriageStatus fromDbValue(String value) {
        for (TriageStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown TriageStatus dbValue: " + value);
    }
}