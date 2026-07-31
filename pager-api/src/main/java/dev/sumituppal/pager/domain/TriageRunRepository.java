package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TriageRun}.
 *
 * <p>Beyond the standard CRUD from {@link JpaRepository}, we expose two
 * queries the rest of the codebase actually needs:
 * <ol>
 *   <li>{@link #findByIdempotencyKey(String)} — the ingress handler uses
 *       this to reject retried webhooks. Spring Data derives the query
 *       from the method name.</li>
 *   <li>{@link #findRecent(int)} — the dashboard's "last N triages" list.
 *       Spring Data derives it from the method name; the {@code Pageable}
 *       version would work too but we want a simple bounded query.</li>
 * </ol>
 */
@Repository
public interface TriageRunRepository extends JpaRepository<TriageRun, String> {

    Optional<TriageRun> findByIdempotencyKey(String idempotencyKey);

    List<TriageRun> findTop50ByOrderByCreatedAtDesc();

    List<TriageRun> findByIncidentIdOrderByCreatedAtDesc(String incidentId);
}