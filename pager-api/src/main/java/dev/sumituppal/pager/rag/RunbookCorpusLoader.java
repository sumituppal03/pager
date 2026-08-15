package dev.sumituppal.pager.rag;

import dev.sumituppal.pager.domain.IdGenerator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the {@code documents} table with sample runbooks on first startup.
 *
 * <p>Runs at {@code @PostConstruct} time on a background thread. Idempotent
 * on the top-level check but resilient to per-document failures — a bad
 * embedding for one doc doesn't lose the rest.
 *
 * <p>In production, runbooks would be ingested from git, Confluence, or
 * Notion with a scheduled sync job. This loader is enough for demo.
 *
 * <h2>Transaction handling</h2>
 * <p>{@code @Transactional} on {@code saveOne} works because we call it
 * through the repository proxies ({@code documents.save} and
 * {@code embeddings.insertNative}). Each save opens its own tx via the
 * Spring Data JPA proxy — regardless of how {@code saveOne} itself was
 * invoked.
 *
 * <h2>Why native insert for embeddings?</h2>
 * <p>Hibernate 6 doesn't know how to bind a {@code String} parameter to
 * a pgvector column type. Entity {@code save()} on {@link DocumentEmbedding}
 * fails silently because the JDBC type mapping is wrong. The native
 * {@code INSERT ... CAST(? AS vector)} works because Postgres does the
 * parse from string to vector explicitly.
 */
@Component
public class RunbookCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(RunbookCorpusLoader.class);

    private final DocumentRepository documents;
    private final DocumentEmbeddingRepository embeddings;
    private final EmbeddingClient embeddingClient;

    public RunbookCorpusLoader(
            DocumentRepository documents,
            DocumentEmbeddingRepository embeddings,
            EmbeddingClient embeddingClient) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.embeddingClient = embeddingClient;
    }

    @PostConstruct
    public void seedIfEmpty() {
        // Fire-and-forget on a background thread so app boot completes
        // quickly. The embedder may not even be up yet.
        new Thread(this::doSeedSafely, "pager-rag-seed").start();
    }

    /**
     * Wrapper that runs seed logic with a top-level try/catch. Ensures
     * a crash here never brings down the seeding thread's stack.
     */
    private void doSeedSafely() {
        try {
            doSeed();
        } catch (Exception e) {
            log.error("RAG corpus seeding failed", e);
        }
    }

    private void doSeed() {
        long existing = documents.count();
        if (existing > 0) {
            log.info("RAG corpus already contains {} docs, skipping seed", existing);
            return;
        }

        List<SampleDoc> samples = sampleRunbooks();
        log.info("seeding RAG corpus with {} runbooks...", samples.size());

        // Batch-embed all texts in one call for efficiency.
        List<String> texts = samples.stream()
            .map(s -> s.title + "\n" + s.content)
            .toList();

        List<float[]> vectors;
        try {
            vectors = embeddingClient.embedBatch(texts);
        } catch (Exception e) {
            log.error("corpus seeding failed at embedding step: {}", e.getMessage());
            return;
        }

        if (vectors.size() != samples.size()) {
            log.error("embedding size mismatch: expected {} got {}",
                samples.size(), vectors.size());
            return;
        }

        // Save each doc+embedding pair independently so one failure
        // doesn't kill the rest.
        int saved = 0;
        for (int i = 0; i < samples.size(); i++) {
            try {
                saveOne(samples.get(i), vectors.get(i));
                saved++;
            } catch (Exception e) {
                log.warn("failed to save runbook '{}': {}",
                    samples.get(i).title, e.getMessage());
            }
        }
        log.info("RAG corpus seeded — {}/{} runbooks embedded with {}",
            saved, samples.size(), embeddingClient.modelName());
    }

    /**
     * Save one document + its embedding as a single unit.
     *
     * <p>The {@code embedding} column is a pgvector type that Hibernate
     * can't bind directly, so we use a native SQL insert with an explicit
     * {@code CAST(? AS vector)}. See
     * {@link DocumentEmbeddingRepository#insertNative}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void saveOne(SampleDoc sample, float[] vector) {
        Document doc = new Document();
        doc.setKind(sample.kind);
        doc.setTitle(sample.title);
        doc.setContent(sample.content);
        doc.setMetadata("{}");
        Document savedDoc = documents.save(doc);

        // Native insert bypasses the JPA type-inference bug on pgvector.
        embeddings.insertNative(
            IdGenerator.generate("emb"),
            savedDoc.getId(),
            embeddingClient.modelName(),
            DocumentEmbedding.formatVector(vector)
        );
    }

    private record SampleDoc(String kind, String title, String content) {}

    private static List<SampleDoc> sampleRunbooks() {
        return List.of(
            new SampleDoc("runbook", "Runbook: checkout-service 5xx errors",
                """
                When checkout-service returns elevated 5xx error rates:
                1. Check the recent deploys via `git log --oneline -20` on the
                   checkout-service repo. Recent deploys are the #1 cause.
                2. Check upstream dependencies: payment-gateway and
                   inventory-service. A cascading failure is #2.
                3. Check DB connection pool exhaustion:
                   `SELECT count(*) FROM pg_stat_activity WHERE datname='checkout'`
                4. Rollback the most recent deploy if steps 1-3 suggest it.
                Owner: checkout-team. Slack: #incident-checkout.
                """),

            new SampleDoc("runbook", "Runbook: database connection pool exhausted",
                """
                Symptoms: services returning 5xx with "connection pool timeout"
                or "no more connections" in the logs.

                Immediate mitigation:
                1. Scale up the affected service's pod count to distribute load
                2. Check for long-running queries via pg_stat_activity
                3. Kill queries older than 5 minutes if they're blocking

                Root cause investigation:
                - Recent code changes that added new queries
                - Missing indexes causing table scans
                - Traffic spike beyond capacity
                """),

            new SampleDoc("runbook", "Runbook: upstream service failures",
                """
                When a downstream service starts failing due to an upstream
                dependency:
                1. Confirm the upstream is actually down: hit its /health
                2. If upstream is truly down, enable circuit breaker for
                   the affected caller
                3. Fall back to cached data or degrade gracefully
                4. Post incident update to status page
                Upstream contacts: payment-gateway (#payments), inventory (#inventory)
                """),

            new SampleDoc("runbook", "Runbook: capacity issues and traffic spikes",
                """
                Diagnosing capacity issues:
                - Check request rate vs. baseline in Grafana
                - Check CPU/memory saturation on the affected service pods
                - Check queue depth for async workers

                Mitigations:
                1. Horizontal scale — increase replica count
                2. Enable rate limiting on the ingress if traffic is malicious
                3. Enable request-shedding if traffic is legitimate but excessive
                4. Scale up dependencies (Postgres reads, Redis, downstream APIs)
                """),

            new SampleDoc("postmortem", "Post-mortem: 2025-01-14 checkout-service outage",
                """
                On 2025-01-14 at 03:12 UTC, checkout-service began returning
                5xx errors at a 15% rate. Impact: 340 failed checkouts, $12K
                estimated revenue loss.

                Root cause: A deploy at 03:08 UTC changed the payment-gateway
                timeout from 30s to 3s. Under normal load this was fine, but
                during a peak-traffic minute the tail latency exceeded 3s and
                requests started timing out cascading to 5xx.

                Detection: Alert fired at 03:14 UTC (2 min after incident start).

                Resolution: Rolled back the deploy at 03:22 UTC. Errors dropped
                to baseline within 90 seconds.

                Follow-up: (1) Add tail-latency alerting on payment-gateway.
                (2) Make timeout config a Feature Flag so rollback is instant.
                (3) Load-test any timeout change before deploy.
                """),

            new SampleDoc("postmortem", "Post-mortem: 2024-12-03 database connection storm",
                """
                On 2024-12-03 at 14:32 UTC, multiple services began returning
                connection-pool-timeout errors. Duration: 47 minutes.

                Root cause: A new query in the orders-service (added 2 hours
                earlier via deploy) was missing an index. Under normal load
                the query took 200ms; under Black Friday traffic it hit 15
                seconds and exhausted the shared connection pool.

                Detection: PagerDuty alert fired 3 minutes after saturation.

                Resolution: Added the missing index via emergency migration.
                Connection pool recovered within 60 seconds after index creation.

                Follow-up: (1) Explain-analyze required on all PR checks for
                new queries. (2) Per-service connection pools instead of shared.
                (3) Capacity plan should include Black Friday traffic multiplier.
                """),

            new SampleDoc("runbook", "Runbook: incident response general playbook",
                """
                Standard incident response steps:
                1. Acknowledge the page within 5 minutes
                2. Post to #incident-<service> Slack channel with initial summary
                3. Determine severity (P1-P4) based on user impact
                4. Assign an incident commander if severity is P1 or P2
                5. Investigate root cause via runbooks + observability
                6. Communicate updates every 15 minutes to stakeholders
                7. Restore service — rollback, scaling, or targeted fix
                8. Post-mortem within 3 business days for P1/P2 incidents
                """)
        );
    }
}