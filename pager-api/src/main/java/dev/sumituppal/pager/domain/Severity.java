package dev.sumituppal.pager.domain;

/**
 * Incident / finding severity ladder.
 * P0 is highest (production down); INFO is lowest (noise).
 */
public enum Severity {
    P0, P1, P2, P3, P4, INFO;

    public String dbValue() {
        return name();
    }

    public static Severity fromDbValue(String value) {
        return Severity.valueOf(value);
    }
}
