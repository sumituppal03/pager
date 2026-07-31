package dev.sumituppal.pager.domain;

import java.security.SecureRandom;

/**
 * Generates nanoid-style IDs for all domain entities.
 *
 * <p>Format: {@code <prefix>_<12 chars>} where the alphabet is URL-safe
 * base62. Examples:
 * <pre>
 *   triage_a3f9b2c1x8k4
 *   fnd_9k2mQ7wR5nX3
 *   evt_b5tG8vY2hL9p
 * </pre>
 *
 * <p>Why 12 chars? 62^12 ≈ 3.2×10^21 possible IDs per prefix — a
 * birthday collision at 10 million IDs/sec would take about 800 years.
 *
 * <p>{@link SecureRandom} is thread-safe and reused across calls;
 * seeding is expensive and once-per-JVM is fine.
 */
public final class IdGenerator {

    private static final char[] ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final int ID_LENGTH = 12;

    private static final SecureRandom RNG = new SecureRandom();

    private IdGenerator() {}

    /**
     * Generate an ID with the given prefix, e.g.
     * {@code IdGenerator.generate("triage")} → {@code triage_a3f9b2c1x8k4}.
     */
    public static String generate(String prefix) {
        char[] chars = new char[ID_LENGTH];
        for (int i = 0; i < ID_LENGTH; i++) {
            chars[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        }
        return prefix + "_" + new String(chars);
    }
}