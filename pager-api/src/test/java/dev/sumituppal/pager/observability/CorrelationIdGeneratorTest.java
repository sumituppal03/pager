package dev.sumituppal.pager.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGeneratorTest {

    @Test
    @DisplayName("generates IDs with the correct prefix and length")
    void generatesCorrectFormat() {
        String id = CorrelationIdGenerator.generate();

        assertThat(id).startsWith("req_");
        // "req_" (4 chars) + 11 chars = 15 total
        assertThat(id).hasSize(15);
    }

    @Test
    @DisplayName("only uses characters from the URL-safe alphabet")
    void usesUrlSafeAlphabet() {
        for (int i = 0; i < 100; i++) {
            String id = CorrelationIdGenerator.generate();
            String body = id.substring(4);
            assertThat(body).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    @DisplayName("generated IDs are unique across 10K single-threaded calls")
    void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(CorrelationIdGenerator.generate());
        }
        // If any collision occurred, size < 10_000
        assertThat(ids).hasSize(10_000);
    }

    @Test
    @DisplayName("generated IDs are unique under concurrent access (thread-safe)")
    void idsAreUniqueUnderConcurrency() throws InterruptedException {
        int threads = 16;
        int perThread = 1_000;
        Set<String> ids = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    ids.add(CorrelationIdGenerator.generate());
                }
            });
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // Expect all 16 * 1000 IDs to be unique
        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("isValid accepts freshly generated IDs")
    void isValidAcceptsGeneratedIds() {
        for (int i = 0; i < 100; i++) {
            String id = CorrelationIdGenerator.generate();
            assertThat(CorrelationIdGenerator.isValid(id))
                .as("just-generated ID %s must validate", id)
                .isTrue();
        }
    }

    @Test
    @DisplayName("isValid rejects null, blank, and malformed IDs")
    void isValidRejectsInvalidIds() {
        assertThat(CorrelationIdGenerator.isValid(null)).isFalse();
        assertThat(CorrelationIdGenerator.isValid("")).isFalse();
        assertThat(CorrelationIdGenerator.isValid("   ")).isFalse();

        // Missing prefix
        assertThat(CorrelationIdGenerator.isValid("abcdefghijk")).isFalse();

        // Wrong prefix
        assertThat(CorrelationIdGenerator.isValid("xyz_abcdefghijk")).isFalse();

        // Prefix but wrong length
        assertThat(CorrelationIdGenerator.isValid("req_short")).isFalse();
        assertThat(CorrelationIdGenerator.isValid("req_wayTooLongForOurFormat")).isFalse();

        // Prefix + correct length but bad character
        assertThat(CorrelationIdGenerator.isValid("req_abcd!fghij0")).isFalse();
        assertThat(CorrelationIdGenerator.isValid("req_abcd fghij0")).isFalse();
    }
}
