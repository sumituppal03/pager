package dev.sumituppal.pager.domain;

/**
 * The four specialist agents plus the aggregator.
 * Matches the CHECK constraint in V1__initial_schema.sql.
 */
public enum Specialist {
    SYMPTOMS("symptoms"),
    CHANGE("change"),
    METRICS("metrics"),
    COMMS("comms"),
    AGGREGATOR("aggregator");

    private final String dbValue;

    Specialist(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Specialist fromDbValue(String value) {
        for (Specialist s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown Specialist dbValue: " + value);
    }
}