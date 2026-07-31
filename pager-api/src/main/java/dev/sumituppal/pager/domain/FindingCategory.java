package dev.sumituppal.pager.domain;

/**
 * The category of what caused an incident finding.
 * Matches the CHECK constraint in V1__initial_schema.sql.
 */
public enum FindingCategory {
    DEPLOY_REGRESSION("deploy_regression"),
    UPSTREAM_FAILURE("upstream_failure"),
    CAPACITY("capacity"),
    DATA_QUALITY("data_quality"),
    CONFIG_CHANGE("config_change"),
    FEATURE_FLAG("feature_flag"),
    THIRD_PARTY_OUTAGE("third_party_outage"),
    UNKNOWN("unknown");

    private final String dbValue;

    FindingCategory(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static FindingCategory fromDbValue(String value) {
        for (FindingCategory c : values()) {
            if (c.dbValue.equals(value)) return c;
        }
        throw new IllegalArgumentException("Unknown FindingCategory dbValue: " + value);
    }
}