package dev.sumituppal.pager.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, String> {

    /**
     * The most recent notification for a triage — a triage should
     * normally have exactly one, but resilience to duplicates is cheap.
     */
    Optional<NotificationRecord> findFirstByTriageIdOrderByCreatedAtDesc(String triageId);

    /**
     * All notification records for a triage — used by the audit
     * dashboard and future replay tooling.
     */
    List<NotificationRecord> findByTriageIdOrderByCreatedAtDesc(String triageId);
}