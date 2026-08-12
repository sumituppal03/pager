package dev.sumituppal.pager.domain;

/**
 * What the HITL gate decided to do with a triage's notification.
 *
 * <h2>Values</h2>
 * <ul>
 *   <li>{@link #AUTO_POSTED} — confidence + category both cleared the
 *       threshold; the message was dispatched to the notification sink.</li>
 *   <li>{@link #AWAITING_REVIEW} — one or more safety checks failed
 *       (low confidence, UNKNOWN category). Message was NOT dispatched;
 *       a {@code hitl_approvals} row was created for a human to review.</li>
 *   <li>{@link #SUPPRESSED} — the gate decided this triage should not
 *       produce a notification at all (e.g. all specialists returned
 *       UNKNOWN and the aggregator has no useful summary). Nothing was
 *       sent, no approval was created.</li>
 * </ul>
 *
 * <p>Mirrors the schema CHECK constraint values via {@link #dbValue()}.
 */
public enum NotificationDecision {
    AUTO_POSTED("auto_posted"),
    AWAITING_REVIEW("awaiting_review"),
    SUPPRESSED("suppressed");

    private final String dbValue;

    NotificationDecision(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static NotificationDecision fromDbValue(String value) {
        for (NotificationDecision d : values()) {
            if (d.dbValue.equals(value)) return d;
        }
        throw new IllegalArgumentException(
            "Unknown NotificationDecision dbValue: " + value);
    }
}