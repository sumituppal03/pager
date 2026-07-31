package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link Finding}.
 *
 * <p>The aggregator queries by {@code triageId} to collect all findings
 * for one triage before merging and dedup.
 */
@Repository
public interface FindingRepository extends JpaRepository<Finding, String> {

    List<Finding> findByTriageIdOrderByConfidenceDesc(String triageId);

    long countByTriageId(String triageId);
}