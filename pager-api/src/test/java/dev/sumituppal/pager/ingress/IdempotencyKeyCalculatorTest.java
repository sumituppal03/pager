package dev.sumituppal.pager.ingress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link IdempotencyKeyCalculator}.
 *
 * <p>The properties we care about, ordered by importance:
 * <ol>
 *   <li>Same inputs → same key (deterministic)</li>
 *   <li>Different payload → different key (payload-sensitive)</li>
 *   <li>Different incident id → different key</li>
 *   <li>Boundary safety: the separator prevents length-extension collisions</li>
 *   <li>Null inputs throw, don't return a null-looking key</li>
 *   <li>Output shape: 40 lowercase hex chars</li>
 * </ol>
 */
class IdempotencyKeyCalculatorTest {

    private static final String INCIDENT = "PGRXXXX";
    private static final byte[] PAYLOAD =
        "{\"event\":{\"id\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("same inputs produce the same key")
    void deterministic() {
        String k1 = IdempotencyKeyCalculator.compute(INCIDENT, PAYLOAD);
        String k2 = IdempotencyKeyCalculator.compute(INCIDENT, PAYLOAD);
        assertThat(k1).isEqualTo(k2);
    }

    @Test
    @DisplayName("different payload produces a different key")
    void payloadSensitive() {
        byte[] mutated = "{\"event\":{\"id\":\"abd\"}}".getBytes(StandardCharsets.UTF_8);
        String k1 = IdempotencyKeyCalculator.compute(INCIDENT, PAYLOAD);
        String k2 = IdempotencyKeyCalculator.compute(INCIDENT, mutated);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("different incident id produces a different key")
    void incidentSensitive() {
        String k1 = IdempotencyKeyCalculator.compute("PGRXXXX", PAYLOAD);
        String k2 = IdempotencyKeyCalculator.compute("PGRYYYY", PAYLOAD);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("separator prevents length-extension collision")
    void separatorPreventsCollision() {
        // Without a separator, ("ab", "cd") and ("a", "bcd") would hash the same.
        // With "::" between them, they cannot collide.
        String a = IdempotencyKeyCalculator.compute("ab", "cd".getBytes(StandardCharsets.UTF_8));
        String b = IdempotencyKeyCalculator.compute("a",  "bcd".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("null incidentId throws")
    void rejectsNullIncident() {
        assertThatThrownBy(() -> IdempotencyKeyCalculator.compute(null, PAYLOAD))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null payload throws")
    void rejectsNullPayload() {
        assertThatThrownBy(() -> IdempotencyKeyCalculator.compute(INCIDENT, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("output is exactly 40 lowercase hex chars")
    void outputShape() {
        String key = IdempotencyKeyCalculator.compute(INCIDENT, PAYLOAD);
        assertThat(key).hasSize(40);
        assertThat(key).matches("[0-9a-f]+");
    }
}