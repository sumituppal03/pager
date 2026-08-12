package dev.sumituppal.pager.hitl;

import dev.sumituppal.pager.config.PagerProperties;
import dev.sumituppal.pager.domain.FindingCategory;
import dev.sumituppal.pager.domain.NotificationDecision;
import dev.sumituppal.pager.domain.NotificationRecord;
import dev.sumituppal.pager.domain.NotificationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class HitlGate {

    private static final Logger log = LoggerFactory.getLogger(HitlGate.class);

    private final NotificationSink sink;
    private final NotificationRecordRepository records;
    private final BigDecimal threshold;

    public HitlGate(
            NotificationSink sink,
            NotificationRecordRepository records,
            PagerProperties properties) {
        this.sink = sink;
        this.records = records;
        this.threshold = properties.confidenceAutopostThreshold();
    }

    @Transactional
    public HitlDecisionResult gate(
            String triageId,
            FindingCategory category,
            BigDecimal confidence,
            String summary) {

        HitlDecisionResult result = decide(triageId, category, confidence, summary);
        persistRecord(triageId, result);
        if (result.decision() == NotificationDecision.AUTO_POSTED) {
            sink.send(triageId, result.message());
        }
        return result;
    }

    private HitlDecisionResult decide(
            String triageId,
            FindingCategory category,
            BigDecimal confidence,
            String summary) {

        String safeSummary = summary == null ? "" : summary;
        BigDecimal safeConfidence = confidence == null ? BigDecimal.ZERO : confidence;

        if (safeSummary.isBlank()) {
            String reason = "blank summary — nothing to notify";
            log.info("triage {} SUPPRESSED: {}", triageId, reason);
            return new HitlDecisionResult(
                NotificationDecision.SUPPRESSED, "", sink.channel(), reason);
        }

        String message = renderMessage(triageId, category, safeConfidence, safeSummary);

        if (category == FindingCategory.UNKNOWN) {
            String reason = "aggregator category is UNKNOWN — human review required";
            log.info("triage {} AWAITING_REVIEW: {}", triageId, reason);
            return new HitlDecisionResult(
                NotificationDecision.AWAITING_REVIEW, message, sink.channel(), reason);
        }

        if (safeConfidence.compareTo(threshold) < 0) {
            String reason = String.format(
                "confidence %s below auto-post threshold %s",
                safeConfidence.toPlainString(), threshold.toPlainString());
            log.info("triage {} AWAITING_REVIEW: {}", triageId, reason);
            return new HitlDecisionResult(
                NotificationDecision.AWAITING_REVIEW, message, sink.channel(), reason);
        }

        String reason = String.format(
            "confidence %s >= threshold %s and category %s is actionable",
            safeConfidence.toPlainString(), threshold.toPlainString(),
            category.dbValue());
        log.info("triage {} AUTO_POSTED: {}", triageId, reason);
        return new HitlDecisionResult(
            NotificationDecision.AUTO_POSTED, message, sink.channel(), reason);
    }
    private String renderMessage(
            String triageId,
            FindingCategory category,
            BigDecimal confidence,
            String summary) {
        return String.format(
            "[Pager] triage=%s category=%s confidence=%s%n%s",
            triageId, category.dbValue(), confidence.toPlainString(), summary);
    }

    private void persistRecord(String triageId, HitlDecisionResult result) {
        try {
            NotificationRecord record = new NotificationRecord();
            record.setTriageId(triageId);
            record.decisionEnum(result.decision());
            record.setChannel(result.channel());
            record.setPayload(result.message());
            records.save(record);
        } catch (Exception e) {
            // Record write is best-effort audit; do not fail the gate
            // decision if the DB blips.
            log.error("failed to persist notification record for triage {}: {}",
                triageId, e.getMessage());
        }
    }
}