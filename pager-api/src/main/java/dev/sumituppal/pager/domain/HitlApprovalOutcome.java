package dev.sumituppal.pager.domain;

/**
 * Outcome of a human-in-the-loop approval request for a suggested write-action.
 * Matches the CHECK constraint in V1__initial_schema.sql.
 */
public enum HitlApprovalOutcome {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    ESCALATED("escalated"),
    EXPIRED("expired");

    private final String dbValue;

    HitlApprovalOutcome(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static HitlApprovalOutcome fromDbValue(String value) {
        for (HitlApprovalOutcome o : values()) {
            if (o.dbValue.equals(value)) return o;
        }
        throw new IllegalArgumentException("Unknown HitlApprovalOutcome dbValue: " + value);
    }
}