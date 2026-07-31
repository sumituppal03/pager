package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link HitlApproval}.
 *
 * <p>The HITL dashboard queries pending approvals — the approval-queue
 * view. Ordered by creation time so oldest surfaces first.
 */
@Repository
public interface HitlApprovalRepository extends JpaRepository<HitlApproval, String> {

    List<HitlApproval> findByOutcomeOrderByCreatedAtAsc(String outcome);

    List<HitlApproval> findByTriageIdOrderByCreatedAtAsc(String triageId);
}