package dev.sumituppal.pager.observability;

import java.security.SecureRandom;

/**
 * Generates correlation IDs for incoming requests.
 *
 * <p>Correlation IDs let us grep the entire lifecycle of one request across
 * threads, async boundaries, LLM calls, and worker jobs. When something
 * goes wrong at 3 AM, the operator finds the failing line and searches for
 * its {@code correlationId} — every log line for that request appears in
 * order, regardless of which thread emitted it.
 *
 * <h2>Format</h2>
 * IDs look like {@code req_a3f9b2c1x8k} — a short prefix followed by 11
 * URL-safe characters from a 62-symbol alphabet. That's 62^11 ≈ 5.2×10^19
 * possible IDs, more than enough that a birthday collision is negligible
 * even at 10^6 requests/second.
 *
 * <h2>Why not UUID?</h2>
 * UUIDs are 36 characters with hyphens ({@code 550e8400-e29b-41d4-a716-446655440000}) —
 * ugly in logs, awkward to copy-paste, and mostly wasted entropy.
 * Nanoid-style short IDs are the current industry standard for request IDs
 * (see Vercel, Stripe, Linear).
 *
 * <h2>Thread safety</h2>
 * Uses a shared {@link SecureRandom} instance. {@code SecureRandom} is
 * documented as thread-safe in the JDK.
 */
public final class CorrelationIdGenerator {

    /** URL-safe alphabet: [0-9A-Za-z]. Avoids ambiguous chars like -_ */
    private static final char[] ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final int ID_LENGTH = 11;
    private static final String PREFIX = "req_";

    // SecureRandom is thread-safe and doesn't require synchronization.
    // Reusing one instance avoids the seeding cost on every request.
    private static final SecureRandom RNG = new SecureRandom();

    private CorrelationIdGenerator() {
        // Non-instantiable
    }

    /**
     * Generate a fresh correlation ID, e.g. {@code req_a3f9b2c1x8k}.
     */
    public static String generate() {
        char[] chars = new char[ID_LENGTH];
        for (int i = 0; i < ID_LENGTH; i++) {
            chars[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        }
        return PREFIX + new String(chars);
    }

    /**
     * Return true if {@code candidate} looks like a well-formed correlation ID.
     * Used by the filter to decide whether to trust an inbound header value
     * or generate a fresh one instead.
     */
    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != PREFIX.length() + ID_LENGTH) {
            return false;
        }
        if (!candidate.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean isDigit = c >= '0' && c <= '9';
            boolean isUpper = c >= 'A' && c <= 'Z';
            boolean isLower = c >= 'a' && c <= 'z';
            if (!isDigit && !isUpper && !isLower) {
                return false;
            }
        }
        return true;
    }
}
