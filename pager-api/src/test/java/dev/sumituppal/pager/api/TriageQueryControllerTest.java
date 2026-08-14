package dev.sumituppal.pager.api;

import dev.sumituppal.pager.domain.AgentEvent;
import dev.sumituppal.pager.domain.AgentEventRepository;
import dev.sumituppal.pager.domain.Finding;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.FindingRepository;
import dev.sumituppal.pager.domain.NotificationDecision;
import dev.sumituppal.pager.domain.NotificationRecord;
import dev.sumituppal.pager.domain.NotificationRecordRepository;
import dev.sumituppal.pager.domain.Severity;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.domain.TriageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriageQueryController}.
 *
 * <p>Focus: response DTOs are correctly shaped from entities, and the
 * aggregator-finding lookup on the list view picks the right specialist
 * row. All repository interactions mocked.
 */
class TriageQueryControllerTest {

    private TriageRunRepository triageRuns;
    private FindingRepository findings;
    private NotificationRecordRepository notifications;
    private AgentEventRepository events;
    private TriageQueryController controller;

    @BeforeEach
    void setUp() {
        triageRuns = mock(TriageRunRepository.class);
        findings = mock(FindingRepository.class);
        notifications = mock(NotificationRecordRepository.class);
        events = mock(AgentEventRepository.class);
        controller = new TriageQueryController(triageRuns, findings, notifications, events);
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/triages — list
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list returns triages with aggregator category and confidence")
    void listIncludesAggregatorFields() {
        TriageRun triage = newCompletedTriage("triage_1");
        Page<TriageRun> page = new PageImpl<>(List.of(triage));
        when(triageRuns.findAll(any(Pageable.class))).thenReturn(page);

        Finding aggregatorFinding = newFinding(
            Specialist.AGGREGATOR, FindingCategory.DEPLOY_REGRESSION, "0.80", "merged");
        Finding symptomsFinding = newFinding(
            Specialist.SYMPTOMS, FindingCategory.UNKNOWN, "0.85", "symptom text");

        when(findings.findByTriageIdOrderByConfidenceDesc("triage_1"))
            .thenReturn(List.of(symptomsFinding, aggregatorFinding));

        NotificationRecord notif = newNotification("triage_1", NotificationDecision.AUTO_POSTED);
        when(notifications.findFirstByTriageIdOrderByCreatedAtDesc("triage_1"))
            .thenReturn(Optional.of(notif));

        List<TriageListView> result = controller.list(50);

        assertThat(result).hasSize(1);
        TriageListView view = result.get(0);
        assertThat(view.id()).isEqualTo("triage_1");
        assertThat(view.aggregatedCategory()).isEqualTo("deploy_regression");
        assertThat(view.aggregatedConfidence()).isEqualByComparingTo("0.80");
        assertThat(view.notificationDecision()).isEqualTo("AUTO_POSTED");
    }

    @Test
    @DisplayName("list handles triages without aggregator finding (still in-flight)")
    void listHandlesTriageWithoutAggregator() {
        TriageRun triage = newQueuedTriage("triage_pending");
        Page<TriageRun> page = new PageImpl<>(List.of(triage));
        when(triageRuns.findAll(any(Pageable.class))).thenReturn(page);
        when(findings.findByTriageIdOrderByConfidenceDesc("triage_pending"))
            .thenReturn(List.of());
        when(notifications.findFirstByTriageIdOrderByCreatedAtDesc("triage_pending"))
            .thenReturn(Optional.empty());

        List<TriageListView> result = controller.list(50);

        assertThat(result).hasSize(1);
        TriageListView view = result.get(0);
        assertThat(view.aggregatedCategory()).isNull();
        assertThat(view.aggregatedConfidence()).isNull();
        assertThat(view.notificationDecision()).isNull();
    }

    @Test
    @DisplayName("limit is clamped to at most 200")
    void limitIsClamped() {
        when(triageRuns.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        controller.list(500);   // should be silently clamped to 200
        controller.list(-100);  // should be clamped to 1
        // No exception is the assertion — just verifying it doesn't blow up
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/triages/{id} — detail
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detail returns 200 with full findings + notification + events")
    void detailReturnsFullView() {
        TriageRun triage = newCompletedTriage("triage_full");
        when(triageRuns.findById("triage_full")).thenReturn(Optional.of(triage));

        Finding f1 = newFinding(Specialist.SYMPTOMS, FindingCategory.UNKNOWN, "0.85", "s");
        Finding f2 = newFinding(Specialist.AGGREGATOR, FindingCategory.DEPLOY_REGRESSION, "0.80", "m");
        when(findings.findByTriageIdOrderByConfidenceDesc("triage_full"))
            .thenReturn(List.of(f1, f2));

        NotificationRecord notif = newNotification("triage_full", NotificationDecision.AUTO_POSTED);
        when(notifications.findFirstByTriageIdOrderByCreatedAtDesc("triage_full"))
            .thenReturn(Optional.of(notif));

       AgentEvent event = new AgentEvent();
        event.setId("evt_1");
        event.setTriageId("triage_full");
        event.setTs(OffsetDateTime.now());
        event.setEventType("span.start");
        event.setSpecialist("symptoms");
        event.setSpanId("span_ABC");
        when(events.findByTriageIdOrderByTsAsc("triage_full")).thenReturn(List.of(event));

        ResponseEntity<TriageDetailView> response = controller.detail("triage_full");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        TriageDetailView body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo("triage_full");
        assertThat(body.findings()).hasSize(2);
        assertThat(body.notification()).isNotNull();
        assertThat(body.notification().decision()).isEqualTo("auto_posted");
        assertThat(body.events()).hasSize(1);
        assertThat(body.events().get(0).eventType()).isEqualTo("span.start");
    }

    @Test
    @DisplayName("detail returns 404 for unknown triage id")
    void detailReturns404ForUnknownId() {
        when(triageRuns.findById(anyString())).thenReturn(Optional.empty());

        ResponseEntity<TriageDetailView> response = controller.detail("triage_missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("detail with no notification returns null notification field")
    void detailWithoutNotification() {
        TriageRun triage = newCompletedTriage("triage_no_notif");
        when(triageRuns.findById("triage_no_notif")).thenReturn(Optional.of(triage));
        when(findings.findByTriageIdOrderByConfidenceDesc("triage_no_notif"))
            .thenReturn(List.of());
        when(notifications.findFirstByTriageIdOrderByCreatedAtDesc("triage_no_notif"))
            .thenReturn(Optional.empty());
        when(events.findByTriageIdOrderByTsAsc("triage_no_notif"))
            .thenReturn(List.of());

        ResponseEntity<TriageDetailView> response = controller.detail("triage_no_notif");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().notification()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private static TriageRun newQueuedTriage(String id) {
        TriageRun t = new TriageRun();
        t.setId(id);
        t.setIdempotencyKey("idem-" + id);
        t.setIncidentId("PGR-DEMO-001");
        t.setAlertSummary("test alert");
        t.severityEnum(Severity.P2);
        t.statusEnum(TriageStatus.QUEUED);
        t.setRawPayload("{}");
        t.setCreatedAt(OffsetDateTime.now());
        return t;
    }

    private static TriageRun newCompletedTriage(String id) {
        TriageRun t = newQueuedTriage(id);
        t.statusEnum(TriageStatus.COMPLETED);
        t.setStartedAt(OffsetDateTime.now().minusSeconds(5));
        t.setCompletedAt(OffsetDateTime.now());
        t.setAggregatedSummary("aggregated summary text");
        return t;
    }

    private static Finding newFinding(
            Specialist specialist,
            FindingCategory category,
            String confidence,
            String summary) {
        Finding f = new Finding();
        f.setId("fnd_" + specialist.name());
        f.setTriageId("triage_1");
        f.specialistEnum(specialist);
        f.categoryEnum(category);
        f.severityEnum(Severity.P2);
        f.setConfidence(new BigDecimal(confidence));
        f.setSummary(summary);
        f.setRationale("{}");
        f.setCreatedAt(OffsetDateTime.now());
        return f;
    }

    private static NotificationRecord newNotification(
            String triageId, NotificationDecision decision) {
        NotificationRecord n = new NotificationRecord();
        n.setId("notif_" + triageId);
        n.setTriageId(triageId);
        n.decisionEnum(decision);
        n.setChannel("log");
        n.setPayload("test message");
        n.setCreatedAt(OffsetDateTime.now());
        return n;
    }
}