package dev.sumituppal.pager.api;

import dev.sumituppal.pager.api.TriageDetailView.AgentEventView;
import dev.sumituppal.pager.api.TriageDetailView.FindingView;
import dev.sumituppal.pager.api.TriageDetailView.NotificationView;
import dev.sumituppal.pager.domain.AgentEvent;
import dev.sumituppal.pager.domain.AgentEventRepository;
import dev.sumituppal.pager.domain.Finding;
import dev.sumituppal.pager.domain.FindingRepository;
import dev.sumituppal.pager.domain.NotificationRecord;
import dev.sumituppal.pager.domain.NotificationRecordRepository;
import dev.sumituppal.pager.domain.Specialist;
import dev.sumituppal.pager.domain.TriageRun;
import dev.sumituppal.pager.domain.TriageRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Read-only REST endpoints for the frontend dashboard.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/triages} — list of triages, most recent first,
 *       with pagination via {@code ?limit=} (default 50, max 200)</li>
 *   <li>{@code GET /api/triages/{id}} — full detail including findings,
 *       notification, and events for the trace viewer</li>
 * </ul>
 *
 * <h2>Why separate GET endpoints instead of one big one?</h2>
 * <p>The list page needs different data than the detail page. Overfetching
 * on the list (loading all findings for every row) hurts performance
 * needlessly. Underfetching on the detail forces a waterfall of extra
 * calls. Two shapes, two endpoints, each optimized for its use case.
 *
 * <h2>Aggregated fields on list rows</h2>
 * <p>The list view carries {@code aggregatedCategory} and
 * {@code aggregatedConfidence} from the AGGREGATOR finding, so users can
 * see the causal category and safety-gate signal without opening the
 * detail. If aggregator hasn't run yet (rare in-flight case), the fields
 * are null.
 */
@RestController
@RequestMapping("/api/triages")
public class TriageQueryController {

    private final TriageRunRepository triageRuns;
    private final FindingRepository findings;
    private final NotificationRecordRepository notifications;
    private final AgentEventRepository events;

    public TriageQueryController(
            TriageRunRepository triageRuns,
            FindingRepository findings,
            NotificationRecordRepository notifications,
            AgentEventRepository events) {
        this.triageRuns = triageRuns;
        this.findings = findings;
        this.notifications = notifications;
        this.events = events;
    }

    @GetMapping
    public List<TriageListView> list(
            @RequestParam(defaultValue = "50") int limit) {
        int clampedLimit = Math.max(1, Math.min(200, limit));
        // Sort by createdAt DESC — most recent triages first.
        return triageRuns.findAll(
                PageRequest.of(0, clampedLimit,
                    Sort.by(Sort.Direction.DESC, "createdAt"))
            ).stream()
            .map(this::toListView)
            .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TriageDetailView> detail(@PathVariable String id) {
        Optional<TriageRun> maybeTriage = triageRuns.findById(id);
        if (maybeTriage.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetailView(maybeTriage.get()));
    }

    // ---- mappers ----

    private TriageListView toListView(TriageRun t) {
        // Find the aggregator finding for this triage, if it exists.
        Optional<Finding> aggregatorFinding = findings.findByTriageIdOrderByConfidenceDesc(t.getId())
            .stream()
            .filter(f -> f.specialistEnum() == Specialist.AGGREGATOR)
            .findFirst();

        Optional<NotificationRecord> notification =
            notifications.findFirstByTriageIdOrderByCreatedAtDesc(t.getId());

        return new TriageListView(
            t.getId(),
            t.getIncidentId(),
            t.getAlertSummary(),
            t.getService(),
            t.severityEnum() != null ? t.severityEnum().name() : null,
            t.statusEnum() != null ? t.statusEnum().name() : null,
            aggregatorFinding.map(f -> f.categoryEnum().dbValue()).orElse(null),
            aggregatorFinding.map(Finding::getConfidence).orElse(null),
            t.getAggregatedSummary(),
            notification.map(n -> n.decisionEnum().name()).orElse(null),
            t.getCreatedAt(),
            t.getCompletedAt()
        );
    }

    private TriageDetailView toDetailView(TriageRun t) {
        List<FindingView> findingViews = findings.findByTriageIdOrderByConfidenceDesc(t.getId())
            .stream()
            .map(f -> new FindingView(
                f.getId(),
                f.specialistEnum() != null ? f.specialistEnum().dbValue() : null,
                f.categoryEnum() != null ? f.categoryEnum().dbValue() : null,
                f.severityEnum() != null ? f.severityEnum().name() : null,
                f.getConfidence(),
                f.getSummary(),
                f.getRationale(),
                f.getCreatedAt()
            ))
            .toList();

        NotificationView notification = notifications
            .findFirstByTriageIdOrderByCreatedAtDesc(t.getId())
            .map(n -> new NotificationView(
                n.getId(),
                n.decisionEnum().dbValue(),
                n.getChannel(),
                n.getPayload(),
                n.getCreatedAt()
            ))
            .orElse(null);

        List<AgentEventView> eventViews = events.findByTriageIdOrderByTsAsc(t.getId())
            .stream()
            .map(e -> new AgentEventView(
                e.getId(),
                e.getTs(),
                e.getEventType(),
                e.getSpecialist(),
                e.getSpanId(),
                e.getParentSpanId(),
                e.getModel(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getLatencyMs(),
                e.getOutcome()
            ))
            .toList();

        return new TriageDetailView(
            t.getId(),
            t.getIncidentId(),
            t.getAlertSummary(),
            t.getService(),
            t.severityEnum() != null ? t.severityEnum().name() : null,
            t.statusEnum() != null ? t.statusEnum().name() : null,
            t.getAggregatedSummary(),
            t.getCreatedAt(),
            t.getStartedAt(),
            t.getCompletedAt(),
            findingViews,
            notification,
            eventViews
        );
    }
}