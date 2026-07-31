package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Repository for {@link AgentEvent}.
 *
 * <p>The events table is write-heavy and read-heavy in different patterns:
 * <ul>
 *   <li>Writes: append-only, one per span/LLM/tool/decision. Use
 *       {@code saveAll()} in batches from {@code EventEmitter} (PR #7)
 *       to amortize the round-trip cost.</li>
 *   <li>Reads: the trace viewer queries one triage's events in time order
 *       — served cheaply by the {@code agent_events_triage_ts_idx}
 *       composite index from PR 4a. The cost ledger queries by time range
 *       — served by the BRIN index.</li>
 * </ul>
 */
@Repository
public interface AgentEventRepository extends JpaRepository<AgentEvent, String> {

    List<AgentEvent> findByTriageIdOrderByTsAsc(String triageId);

    List<AgentEvent> findByTsBetweenOrderByTsAsc(OffsetDateTime from, OffsetDateTime to);
}