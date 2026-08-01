package dev.sumituppal.pager.ingress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes idempotency keys for incoming webhooks.
 *
 * <p>The key is {@code sha256(incidentId + "::" + rawPayloadBytes)},
 * hex-encoded, first 40 chars. That gives us:
 * <ul>
 *   <li><strong>Deterministic</strong> — same webhook → same key, always.
 *       Two PagerDuty retries of the same event compute the same key,
 *       so the DB's unique constraint on
 *       {@code triage_runs.idempotency_key} rejects the second one.</li>
 *   <li><strong>Payload-sensitive</strong> — if PagerDuty resends a
 *       <em>modified</em> event (e.g. escalation from P2 → P1 with the
 *       same incident ID), we get a different key and process it as a
 *       new triage. Correct behavior.</li>
 *   <li><strong>Bounded</strong> — 40 hex chars fits comfortably in an
 *       indexed TEXT column and has 160 bits of collision resistance,
 *       which is astronomically safe.</li>
 * </ul>
 *
 * <p>Note the {@code "::"} separator — required to prevent length-extension
 * ambiguity. Without it, {@code ("abc", "def")} and {@code ("ab", "cdef")}
 * would hash to the same value.
 */
public final class IdempotencyKeyCalculator {

    private static final int KEY_LENGTH_CHARS = 40;
    private static final String SEPARATOR = "::";

    private IdempotencyKeyCalculator() {}

    /**
     * Compute the idempotency key for a webhook.
     *
     * @param incidentId  the upstream incident ID (from the payload's
     *                    parsed body, or the raw event id if that's missing)
     * @param rawPayload  the exact bytes of the request body — same bytes
     *                    used for HMAC verification. Must be raw, not a
     *                    re-serialized parsed version.
     * @return 40-char hex string, deterministic and stable
     */
    public static String compute(String incidentId, byte[] rawPayload) {
        if (incidentId == null || rawPayload == null) {
            throw new IllegalArgumentException(
                "incidentId and rawPayload must both be non-null");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(incidentId.getBytes(StandardCharsets.UTF_8));
            sha.update(SEPARATOR.getBytes(StandardCharsets.UTF_8));
            sha.update(rawPayload);
            byte[] digest = sha.digest();
            String hex = HexFormat.of().formatHex(digest);
            return hex.substring(0, KEY_LENGTH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JDK. If it's missing, we have
            // bigger problems than webhook idempotency.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}