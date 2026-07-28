package dev.sumituppal.pager.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebhookSignatureVerifier}.
 *
 * <p>These tests fall into three groups:
 * <ol>
 *   <li><strong>Happy paths</strong> — valid signatures verify correctly,
 *       including the rotation case (multiple comma-separated signatures).</li>
 *   <li><strong>Attack surface</strong> — tampered body, tampered signature,
 *       wrong secret, malformed header, null and blank inputs all return
 *       {@code false} rather than throwing.</li>
 *   <li><strong>RFC 4231 conformance</strong> — the underlying HMAC-SHA256
 *       implementation matches the well-known standard test vectors, proving
 *       we're not accidentally computing something else.</li>
 * </ol>
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "webhook-shared-secret";
    private static final String BODY = "{\"incident_id\":\"P123\",\"severity\":\"P1\"}";

    // Pre-computed with: openssl dgst -sha256 -hmac "webhook-shared-secret" (on BODY)
    // Regenerate if you change SECRET or BODY above.
    private static final String VALID_SIG_HEX =
        "5d43aa61c7ebf3a3fc7d1a34c3f16a02bd77e5deacff3f76a5b0011ff5687ea9";

    // ─────────────────────────────────────────────────────────────
    // Happy paths
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid signatures")
    class ValidSignatures {

        @Test
        @DisplayName("verifies a correctly-signed payload")
        void verifiesCorrectlySignedPayload() throws Exception {
            String expected = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            String header = "v1=" + expected;

            assertThat(WebhookSignatureVerifier.verify(BODY, header, SECRET)).isTrue();
        }

        @Test
        @DisplayName("accepts uppercase hex in the header")
        void acceptsUppercaseHex() throws Exception {
            String expected = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            String header = "v1=" + expected.toUpperCase();

            assertThat(WebhookSignatureVerifier.verify(BODY, header, SECRET)).isTrue();
        }

        @Test
        @DisplayName("accepts prefix casing v1=, V1=, V1= (case-insensitive)")
        void acceptsPrefixCasing() throws Exception {
            String expected = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);

            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=" + expected, SECRET)).isTrue();
            assertThat(WebhookSignatureVerifier.verify(BODY, "V1=" + expected, SECRET)).isTrue();
        }

        @Test
        @DisplayName("matches when one of multiple comma-separated signatures is valid (rotation)")
        void matchesDuringSecretRotation() throws Exception {
            String valid = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            String bogus = "0".repeat(64);
            String header = "v1=" + bogus + ",v1=" + valid;

            assertThat(WebhookSignatureVerifier.verify(BODY, header, SECRET)).isTrue();
        }

        @Test
        @DisplayName("tolerates whitespace around comma-separated signatures")
        void toleratesWhitespaceInHeader() throws Exception {
            String valid = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            String header = "  v1=" + "0".repeat(64) + " , v1=" + valid + "  ";

            assertThat(WebhookSignatureVerifier.verify(BODY, header, SECRET)).isTrue();
        }

        @Test
        @DisplayName("verifies a unicode payload byte-for-byte")
        void verifiesUnicodePayload() throws Exception {
            String unicodeBody = "{\"message\":\"日本語 — pages fire at 3 AM 🔥\"}";
            String expected = WebhookSignatureVerifier.computeSignatureHex(unicodeBody, SECRET);

            assertThat(WebhookSignatureVerifier.verify(unicodeBody, "v1=" + expected, SECRET)).isTrue();
        }

        @Test
        @DisplayName("verifies an empty-string body")
        void verifiesEmptyBody() throws Exception {
            String expected = WebhookSignatureVerifier.computeSignatureHex("", SECRET);

            assertThat(WebhookSignatureVerifier.verify("", "v1=" + expected, SECRET)).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Attack surface — every one of these must return false, not throw
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rejects invalid input")
    class InvalidInput {

        @Test
        @DisplayName("rejects a tampered body")
        void rejectsTamperedBody() throws Exception {
            String expected = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            String tamperedBody = BODY.replace("P1", "P0"); // attacker escalates severity

            assertThat(WebhookSignatureVerifier.verify(tamperedBody, "v1=" + expected, SECRET))
                .isFalse();
        }

        @Test
        @DisplayName("rejects a tampered signature (single bit flip)")
        void rejectsTamperedSignature() throws Exception {
            String valid = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);
            // Flip the last hex digit
            char last = valid.charAt(valid.length() - 1);
            char flipped = last == 'f' ? 'e' : (char) (last + 1);
            String tampered = valid.substring(0, valid.length() - 1) + flipped;

            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=" + tampered, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects when the wrong secret was used to sign")
        void rejectsWrongSecret() throws Exception {
            String wrongSig = WebhookSignatureVerifier.computeSignatureHex(BODY, "not-the-secret");

            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=" + wrongSig, SECRET)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("rejects null / blank secret")
        void rejectsBlankSecret(String secret) {
            String header = "v1=" + "0".repeat(64);
            assertThat(WebhookSignatureVerifier.verify(BODY, header, secret)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("rejects null / blank header")
        void rejectsBlankHeader(String header) {
            assertThat(WebhookSignatureVerifier.verify(BODY, header, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects null body")
        void rejectsNullBody() {
            String header = "v1=" + "0".repeat(64);
            assertThat(WebhookSignatureVerifier.verify(null, header, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects a header missing the v1= prefix")
        void rejectsMissingPrefix() throws Exception {
            String valid = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);

            // Just the hex, no v1= prefix
            assertThat(WebhookSignatureVerifier.verify(BODY, valid, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects a header with the wrong scheme version (v2=)")
        void rejectsWrongVersion() throws Exception {
            String valid = WebhookSignatureVerifier.computeSignatureHex(BODY, SECRET);

            assertThat(WebhookSignatureVerifier.verify(BODY, "v2=" + valid, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects a signature that is too short")
        void rejectsShortSignature() {
            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=deadbeef", SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects a signature that is too long")
        void rejectsLongSignature() {
            String tooLong = "0".repeat(66); // 66 chars
            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=" + tooLong, SECRET)).isFalse();
        }

        @Test
        @DisplayName("rejects a signature with non-hex characters")
        void rejectsNonHexSignature() {
            String badHex = "g".repeat(64); // 'g' is not a hex character
            assertThat(WebhookSignatureVerifier.verify(BODY, "v1=" + badHex, SECRET)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RFC 4231 § 4.2 — Test Case 1 for HMAC-SHA-256
    // This proves our underlying HMAC computation matches the standard.
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RFC 4231 conformance")
    class Rfc4231 {

        @Test
        @DisplayName("matches RFC 4231 § 4.2 test case for HMAC-SHA-256")
        void matchesRfc4231TestCase1() throws Exception {
            // Key: 0x0b repeated 20 times.
            // Data: "Hi There"
            // Expected HMAC-SHA-256:
            //   b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7
            //
            // We construct the key as an ASCII string so that the UTF-8 bytes
            // match the raw key bytes required by the RFC. `0x0b` is a control
            // character but is valid in Java strings and UTF-8-encodes to itself.
            String key = String.valueOf((char) 0x0b).repeat(20);
            String data = "Hi There";
            String expected =
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7";

            String actual = WebhookSignatureVerifier.computeSignatureHex(data, key);
            assertThat(actual).isEqualTo(expected);
        }
    }
}