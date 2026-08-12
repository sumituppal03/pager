package dev.sumituppal.pager.hitl;

import dev.sumituppal.pager.domain.NotificationDecision;
public record HitlDecisionResult(
    NotificationDecision decision,
    String message,
    String channel,
    String reason
) {}