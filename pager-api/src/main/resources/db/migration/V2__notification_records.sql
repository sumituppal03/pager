-- Notification records — what got sent (or would have been sent) as a
-- result of a triage completing. Every triage produces exactly one
-- notification record, capturing the HITL gate's decision and the
-- message payload.
--
-- The "channel" concept exists so that when we add real Slack (or
-- email, or PagerDuty back-channel) sinks, each notification remembers
-- which sink delivered it.
CREATE TABLE notification_records (
    id          TEXT PRIMARY KEY,
    triage_id   TEXT NOT NULL,
    -- Gate decision: what did HitlGate decide to do with this triage?
    --   auto_posted     — confidence + category both cleared the bar; message sent
    --   awaiting_review — flagged for human approval; message queued in hitl_approvals
    --   suppressed      — deliberately not sent (e.g. all specialists returned UNKNOWN)
    decision    TEXT NOT NULL,
    channel     TEXT NOT NULL, -- e.g. 'log' (dev), 'slack' (later PR)
    -- The rendered message payload — what a human would read.
    -- Kept even for awaiting_review so approvers see the exact draft.
    payload     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notification_records_decision_check
        CHECK (decision IN ('auto_posted', 'awaiting_review', 'suppressed'))
);

-- Fast lookup by triage — the dashboard will query "what was decided
-- for this triage" often.
CREATE INDEX idx_notification_records_triage ON notification_records(triage_id);

-- Time-ordered scan for the audit log.
CREATE INDEX idx_notification_records_created_at
    ON notification_records(created_at DESC);