package dev.sumituppal.pager.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the JPA entities round-trip through a
 * real Postgres (via Testcontainers), including pgvector and JSONB.
 *
 * <h2>Why Testcontainers instead of H2?</h2>
 * H2 doesn't understand {@code vector}, {@code jsonb}, {@code BRIN} indexes,
 * {@code TIMESTAMPTZ}, or {@code CHECK} constraints the way Postgres does.
 * Testing against H2 would give a green build that fails in production.
 * Testcontainers spins up a real Postgres 16 with pgvector, applies our
 * Flyway migrations, and lets Hibernate hit the actual DB. Slower, but
 * the only way to catch schema-vs-code drift before merge.
 *
 * <h2>Why {@code @DataJpaTest}?</h2>
 * It's the smallest possible slice that gives us the entity manager and
 * repositories, without loading the web layer, security, Redis, or any
 * other bean not relevant to persistence.
 *
 * <h2>Not covered here</h2>
 * We don't test the vector-similarity {@code <=>} operator here — that
 * belongs with the retrieval layer in the RAG PR. Here we only prove
 * the round-trip: encode → insert → read back → decode.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("integration-test")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class DomainPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("pager_test")
        .withUsername("pager")
        .withPassword("pager");

    @DynamicPropertySource
    static void registerDbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired TriageRunRepository triageRuns;
    @Autowired FindingRepository findings;
    @Autowired HitlApprovalRepository approvals;
    @Autowired KnowledgeChunkRepository chunks;
    @Autowired AgentEventRepository events;

    // ─────────────────────────────────────────────────────────────
    // TriageRun round-trip — proves JSONB and enum-as-string mapping
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("TriageRun persists and reads back with all fields intact")
    void triageRunRoundTrips() {
        TriageRun run = new TriageRun();
        run.setIdempotencyKey("idem-" + System.nanoTime());
        run.setIncidentId("PD-INC-42");
        run.setIncidentUrl("https://example.pagerduty.com/incidents/42");
        run.setAlertSummary("checkout-service 5xx spike");
        run.severityEnum(Severity.P1);
        run.setService("checkout");
        run.statusEnum(TriageStatus.RUNNING);
        run.setOverallConfidence(new BigDecimal("0.820"));
        run.setRawPayload("{\"foo\":\"bar\",\"n\":42}");

        TriageRun saved = triageRuns.save(run);
        assertThat(saved.getId()).startsWith("triage_");
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<TriageRun> loaded = triageRuns.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().severityEnum()).isEqualTo(Severity.P1);
        assertThat(loaded.get().statusEnum()).isEqualTo(TriageStatus.RUNNING);
        assertThat(loaded.get().getOverallConfidence()).isEqualByComparingTo("0.820");
        assertThat(loaded.get().getRawPayload()).contains("\"foo\":\"bar\"");
    }

    @Test
    @DisplayName("TriageRun idempotency key is enforced unique")
    void idempotencyKeyIsUnique() {
        String key = "shared-idem-" + System.nanoTime();

        TriageRun a = newRunWithKey(key);
        triageRuns.saveAndFlush(a);

        TriageRun b = newRunWithKey(key);
        assertThat(triageRuns.findByIdempotencyKey(key)).isPresent();

        // Second save must throw due to unique constraint
        assertThat(safeSaveFails(b)).isTrue();
    }

    // ─────────────────────────────────────────────────────────────
    // Finding + cascade delete
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Findings are cascaded-deleted when their TriageRun is deleted")
    void findingsCascadeOnTriageDelete() {
        TriageRun run = triageRuns.saveAndFlush(newRunWithKey("cascade-" + System.nanoTime()));

        Finding f = new Finding();
        f.setTriageId(run.getId());
        f.specialistEnum(Specialist.SYMPTOMS);
        f.severityEnum(Severity.P2);
        f.categoryEnum(FindingCategory.DEPLOY_REGRESSION);
        f.setSummary("Elevated 5xx from checkout-svc pods");
        f.setRationale("Rate went 0.2% → 4.7% at 03:04:22 UTC, matching deploy of pod-checkout-7c9.");
        f.setConfidence(new BigDecimal("0.780"));
        findings.saveAndFlush(f);

        assertThat(findings.countByTriageId(run.getId())).isEqualTo(1L);

        triageRuns.delete(run);
        triageRuns.flush();

        assertThat(findings.countByTriageId(run.getId())).isEqualTo(0L);
    }

    // ─────────────────────────────────────────────────────────────
    // HitlApproval with JSONB suggested_action
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("HitlApproval persists a JSONB suggested action")
    void hitlApprovalPersists() {
        TriageRun run = triageRuns.saveAndFlush(newRunWithKey("hitl-" + System.nanoTime()));

        HitlApproval a = new HitlApproval();
        a.setTriageId(run.getId());
        a.setSuggestedAction("{\"action\":\"rollback\",\"service\":\"checkout\",\"revision\":\"abc123\"}");
        a.setActionDescription("Rollback checkout to previous good revision.");
        approvals.saveAndFlush(a);

        var pending = approvals.findByOutcomeOrderByCreatedAtAsc(
            HitlApprovalOutcome.PENDING.dbValue());
        assertThat(pending).anyMatch(x -> x.getId().equals(a.getId()));
    }

    // ─────────────────────────────────────────────────────────────
    // KnowledgeChunk with pgvector embedding
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("KnowledgeChunk stores a 1536-dim embedding and reads it back")
    void knowledgeChunkEmbeddingRoundTrips() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setSourceType("runbook");
        chunk.setSourceId("runbooks/checkout-5xx.md");
        chunk.setService("checkout");
        chunk.setChunkIndex(0);
        chunk.setContent("If checkout is 5xxing, first check downstream Stripe status.");

        float[] fakeEmbedding = new float[1536];
        for (int i = 0; i < 1536; i++) {
            fakeEmbedding[i] = (float) (i / 1536.0);
        }
        chunk.setEmbeddingVector(fakeEmbedding);

        KnowledgeChunk saved = chunks.saveAndFlush(chunk);

        KnowledgeChunk loaded = chunks.findById(saved.getId()).orElseThrow();
        float[] decoded = loaded.getEmbeddingVector();
        assertThat(decoded).hasSize(1536);
        assertThat(decoded[0]).isEqualTo(0.0f);
        assertThat(decoded[1535]).isCloseTo(1535f / 1536f, within(0.0001f));
    }

    // ─────────────────────────────────────────────────────────────
    // AgentEvent — append-only spine
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AgentEvent persists an LLM-call event with cost + confidence")
    void agentEventPersists() {
        AgentEvent evt = new AgentEvent();
        evt.setTriageId("triage_manualtestid");
        evt.specialistEnum(Specialist.SYMPTOMS);
        evt.setSpanId(IdGenerator.generate("span"));
        evt.eventTypeEnum(AgentEventType.LLM_CALL);
        evt.setModel("gpt-4o-mini");
        evt.setTokensIn(1200);
        evt.setTokensOut(340);
        evt.setCostUsd(new BigDecimal("0.001234"));
        evt.setLatencyMs(2143);
        evt.setConfidence(new BigDecimal("0.720"));
        evt.setPayload("{\"prompt_version\":\"v3\"}");
        AgentEvent saved = events.saveAndFlush(evt);

        AgentEvent loaded = events.findById(saved.getId()).orElseThrow();
        assertThat(loaded.eventTypeEnum()).isEqualTo(AgentEventType.LLM_CALL);
        assertThat(loaded.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(loaded.getCostUsd()).isEqualByComparingTo("0.001234");
        assertThat(loaded.getPayload()).contains("\"prompt_version\":\"v3\"");
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static TriageRun newRunWithKey(String key) {
        TriageRun r = new TriageRun();
        r.setIdempotencyKey(key);
        r.setIncidentId("PD-" + key);
        r.setAlertSummary("test alert");
        r.severityEnum(Severity.P3);
        r.setRawPayload("{}");
        return r;
    }

    private boolean safeSaveFails(TriageRun r) {
        try {
            triageRuns.saveAndFlush(r);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static org.assertj.core.data.Offset<Float> within(float delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}