package dev.sumituppal.pager.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Verifies HMAC-SHA256 signatures on incoming webhook payloads.
 *
 * <p>This is the security primitive that lets us prove an incoming webhook
 * request genuinely came from the sender that shares the secret with us.
 * PagerDuty uses this scheme, as does Slack, Stripe, GitHub, and most
 * modern webhook providers — the header format varies but the underlying
 * algorithm is identical.
 *
 * <h2>PagerDuty's header format</h2>
 * The header is sent as {@code X-PagerDuty-Signature: v1=&lt;hex&gt;}.
 * The value can contain multiple comma-separated signatures during key
 * rotation ({@code v1=&lt;hex1&gt;,v1=&lt;hex2&gt;}); a match on any one
 * is a valid signature. We accept that shape here.
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li><strong>Constant-time comparison.</strong> We use
 *       {@link MessageDigest#isEqual(byte[], byte[])} rather than
 *       {@code Arrays.equals} or {@code String.equals}. A naive comparison
 *       would leak information about how many leading bytes matched via
 *       response-time differences — a real, exploitable side channel.</li>
 *   <li><strong>Case-insensitive hex.</strong> Different implementations
 *       lowercase or uppercase the hex output. We normalize before comparison.</li>
 *   <li><strong>Fail closed.</strong> Null or blank inputs return {@code false}.
 *       Missing algorithm support (impossible on any reasonable JVM) also
 *       returns {@code false} rather than throwing, so ingress code doesn't
 *       need to catch an unlikely exception in the hot path.</li>
 * </ul>
 *
 * <p>This class is stateless and thread-safe. It has no Spring annotations
 * because it's a pure utility — the security layer that consumes it
 * (added in a later PR) will inject the shared secret from configuration
 * and pass it in explicitly.
 */
public final class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "v1=";

    private WebhookSignatureVerifier() {
        // Non-instantiable utility class
    }

    /**
     * Verify that {@code headerValue} contains a valid HMAC-SHA256
     * signature of {@code rawBody} computed with {@code secret}.
     *
     * <p>Returns {@code false} on any invalid input (null, blank, malformed
     * header, wrong signature, tampered payload). Never throws.
     *
     * @param rawBody     The exact bytes of the request body, as received.
     *                    Do <strong>not</strong> re-serialize a parsed payload;
     *                    even semantically identical JSON with different
     *                    whitespace produces a different HMAC.
     * @param headerValue The value of the signature header, e.g.
     *                    {@code "v1=abc123..."} or
     *                    {@code "v1=abc123...,v1=def456..."} during rotation.
     * @param secret      The shared secret configured with the webhook sender.
     * @return {@code true} iff at least one signature in the header matches.
     */
    public static boolean verify(String rawBody, String headerValue, String secret) {
        // Fail closed on any missing input.
        if (rawBody == null || headerValue == null || secret == null) {
            return false;
        }
        if (headerValue.isBlank() || secret.isBlank()) {
            return false;
        }

        // Compute the expected signature once.
        final byte[] expected;
        try {
            expected = computeHmac(rawBody.getBytes(StandardCharsets.UTF_8), secret);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is required by every JDK spec. If it's missing we have
            // bigger problems than an ingress webhook. Fail closed.
            return false;
        }

        // The header may contain multiple comma-separated signatures during
        // secret rotation. Any one match is enough.
        for (String candidate : headerValue.split(",")) {
            byte[] provided = parseSignature(candidate);
            if (provided == null) {
                continue;
            }
            // MessageDigest.isEqual runs in constant time, foiling the
            // timing-attack side channel described in the class javadoc.
            if (MessageDigest.isEqual(expected, provided)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse one {@code v1=<hex>} segment into raw bytes.
     * Returns {@code null} if the segment is malformed.
     */
    private static byte[] parseSignature(String segment) {
        String trimmed = segment.trim();
        if (!trimmed.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            return null;
        }
        String hex = trimmed.substring(SIGNATURE_PREFIX.length());
        // HMAC-SHA256 output is exactly 32 bytes = 64 hex chars.
        if (hex.length() != 64) {
            return null;
        }
        try {
            // HexFormat handles both cases; we normalize implicitly.
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Compute HMAC-SHA256 of {@code data} using {@code secret}.
     * Package-private for direct testing with RFC test vectors.
     */
    static byte[] computeHmac(byte[] data, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(keySpec);
        return mac.doFinal(data);
    }

    /**
     * Compute HMAC-SHA256 of {@code data} using {@code secret}, returning
     * lowercase hex. Handy for tests and for services that want to expose
     * expected signatures for debugging.
     */
    public static String computeSignatureHex(String data, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] sig = computeHmac(data.getBytes(StandardCharsets.UTF_8), secret);
        return HexFormat.of().formatHex(sig);
    }
}