package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link HitlFeedback}.
 *
 * <p>Read-heavy: the continuous-learning PR (much later) will scan this
 * table to reweight retrieval. For now, minimal query surface.
 */
@Repository
public interface HitlFeedbackRepository extends JpaRepository<HitlFeedback, String> {

    List<HitlFeedback> findByTriageIdOrderByCreatedAtDesc(String triageId);
}