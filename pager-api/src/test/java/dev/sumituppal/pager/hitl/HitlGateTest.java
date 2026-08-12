package dev.sumituppal.pager.hitl;

import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.NotificationDecision;
import dev.sumituppal.pager.domain.NotificationRecord;
import dev.sumituppal.pager.domain.NotificationRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HitlGate}.
 *
 * <p>Covers each decision branch, plus the persistence + sink-dispatch
 * contract.
 */
class HitlGateTest {

    private NotificationSink sink;
    private NotificationRecordRepository records;
    private HitlGate gate;

    @BeforeEach
    void setUp() {
        sink = mock(NotificationSink.class);
        when(sink.channel()).thenReturn("log");
        records = mock(NotificationRecordRepository.class);
        when(records.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PagerProperties properties = new PagerProperties(
            new BigDecimal("0.75"),  // threshold under test
            45000L,
            15000L,
            new PagerProperties.Models("m1", "m2", "e1"),
            new BigDecimal("20.00"),
            "pager.triage.queue",
            "test-secret");

        gate = new HitlGate(sink, records, properties);
    }

    // ─────────────────────────────────────────────────────────────
    // AUTO_POSTED path
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("high confidence + real category auto-posts to sink")
    void highConfidenceRealCategoryAutoPosts() {
        HitlDecisionResult result = gate.gate(
            "triage_ok",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.80"),
            "checkout 5xx spike from recent deploy");

        assertThat(result.decision()).isEqualTo(NotificationDecision.AUTO_POSTED);
        assertThat(result.message()).contains("deploy_regression");
        assertThat(result.message()).contains("triage_ok");
        assertThat(result.channel()).isEqualTo("log");

        verify(sink, times(1)).send(eq("triage_ok"), anyString());
        verify(records, times(1)).save(any());
    }

    @Test
    @DisplayName("confidence exactly at threshold auto-posts (inclusive)")
    void confidenceAtThresholdAutoPosts() {
        HitlDecisionResult result = gate.gate(
            "triage_edge",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.75"),
            "a summary");

        assertThat(result.decision()).isEqualTo(NotificationDecision.AUTO_POSTED);
    }

    // ─────────────────────────────────────────────────────────────
    // AWAITING_REVIEW path — low confidence
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("low confidence + real category awaits review, sink not called")
    void lowConfidenceRequiresReview() {
        HitlDecisionResult result = gate.gate(
            "triage_low",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.50"),
            "a summary");

        assertThat(result.decision()).isEqualTo(NotificationDecision.AWAITING_REVIEW);
        assertThat(result.reason()).contains("below auto-post threshold");
        assertThat(result.message()).contains("deploy_regression");

        verify(sink, never()).send(anyString(), anyString());
        verify(records, times(1)).save(any());
    }

    // ─────────────────────────────────────────────────────────────
    // AWAITING_REVIEW path — UNKNOWN category always requires review
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UNKNOWN category always awaits review, even at high confidence")
    void unknownCategoryAlwaysAwaitsReview() {
        HitlDecisionResult result = gate.gate(
            "triage_unknown",
            FindingCategory.UNKNOWN,
            new BigDecimal("0.95"),
            "a summary");

        assertThat(result.decision()).isEqualTo(NotificationDecision.AWAITING_REVIEW);
        assertThat(result.reason()).contains("UNKNOWN");

        verify(sink, never()).send(anyString(), anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // SUPPRESSED path — nothing to say
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("blank summary is suppressed, no sink call, no message text")
    void blankSummaryIsSuppressed() {
        HitlDecisionResult result = gate.gate(
            "triage_blank",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.90"),
            "");

        assertThat(result.decision()).isEqualTo(NotificationDecision.SUPPRESSED);
        assertThat(result.message()).isEmpty();

        verify(sink, never()).send(anyString(), anyString());
        verify(records, times(1)).save(any());
    }

    @Test
    @DisplayName("null summary is treated as blank and suppressed")
    void nullSummaryIsSuppressed() {
        HitlDecisionResult result = gate.gate(
            "triage_null",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.90"),
            null);

        assertThat(result.decision()).isEqualTo(NotificationDecision.SUPPRESSED);
    }

    // ─────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("persisted NotificationRecord carries the decision + payload")
    void persistedRecordCarriesDecisionAndPayload() {
        gate.gate(
            "triage_persist",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.80"),
            "a summary");

        ArgumentCaptor<NotificationRecord> saved =
            ArgumentCaptor.forClass(NotificationRecord.class);
        verify(records, times(1)).save(saved.capture());

        NotificationRecord record = saved.getValue();
        assertThat(record.getTriageId()).isEqualTo("triage_persist");
        assertThat(record.decisionEnum()).isEqualTo(NotificationDecision.AUTO_POSTED);
        assertThat(record.getChannel()).isEqualTo("log");
        assertThat(record.getPayload()).contains("a summary");
    }

    // ─────────────────────────────────────────────────────────────
    // Failure isolation — record persistence failure does not fail gate
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record save failure does not prevent sink send")
    void recordSaveFailureDoesNotBlockSink() {
        when(records.save(any())).thenThrow(new RuntimeException("simulated DB blip"));

        HitlDecisionResult result = gate.gate(
            "triage_dbfail",
            FindingCategory.DEPLOY_REGRESSION,
            new BigDecimal("0.90"),
            "a summary");

        // Decision was still made and sink was still called.
        assertThat(result.decision()).isEqualTo(NotificationDecision.AUTO_POSTED);
        verify(sink, times(1)).send(anyString(), anyString());
    }
}