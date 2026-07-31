-- =====================================================================
-- Pager — V1 initial schema
-- =====================================================================
-- Realizes the three-lane data model from Part II of the architecture
-- study, in one Postgres database with the pgvector extension enabled.
--
--   Lane 1 — Memory:  knowledge_chunks   (vector + full-text)
--   Lane 2 — Truth:   triage_runs, findings, hitl_approvals, hitl_feedback
--   Lane 3 — Time:    agent_events       (append-only, natural time-order)
--
-- Design choices worth noting:
--
--  * IDs are TEXT, not UUID or BIGSERIAL. We generate nanoid-style IDs
--    (~15 chars, URL-safe) in the application layer for consistency
--    with our correlation-ID scheme. TEXT PKs are ~1% slower than
--    integer PKs, which we accept for the readability and URL-safety win.
--
--  * Timestamps are TIMESTAMPTZ. We NEVER use plain TIMESTAMP — timezone
--    confusion is a classic 3 AM incident cause.
--
--  * agent_events uses a BRIN index on `ts`. BRIN is tiny (~1KB per
--    million rows) and perfect for append-only time-series data. This
--    is plain-Postgres's answer to a TimescaleDB hypertable and works
--    well up to tens of millions of rows.
--
--  * Enums are implemented as CHECK constraints + TEXT columns rather
--    than Postgres ENUM types. Adding values to a Postgres ENUM in prod
--    requires ALTER TYPE which has weird transactional limits.
--    CHECK constraints on TEXT are trivial to modify with a new migration.
--
--  * Embedding dimension is 1536, matching OpenAI's text-embedding-3-small
--    default. If we switch models later, that's a new migration.
-- =====================================================================


-- =====================================================================
-- Extensions (idempotent — safe to run against a DB that already has them)
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- =====================================================================
-- Lane 1 — Memory: knowledge_chunks
--
-- Ingested runbooks, past post-mortems, architecture docs.
-- Chunked, embedded, and queried via hybrid (vector + FTS) search
-- at triage time to ground each specialist's LLM call.
-- =====================================================================
CREATE TABLE knowledge_chunks (
    id            TEXT         PRIMARY KEY,
    source_type   TEXT         NOT NULL
                  CHECK (source_type IN ('runbook', 'postmortem', 'arch_doc')),
    source_id     TEXT         NOT NULL,
    service       TEXT,
    chunk_index   INT          NOT NULL,
    content       TEXT         NOT NULL,
    embedding     VECTOR(1536),
    token_count   INT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX knowledge_chunks_embedding_idx
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

ALTER TABLE knowledge_chunks
    ADD COLUMN content_tsv TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX knowledge_chunks_fts_idx
    ON knowledge_chunks USING GIN (content_tsv);

CREATE UNIQUE INDEX knowledge_chunks_source_idx
    ON knowledge_chunks (source_type, source_id, chunk_index);

CREATE INDEX knowledge_chunks_service_idx
    ON knowledge_chunks (service);


-- =====================================================================
-- Lane 2 — Truth: triage_runs
--
-- One row per incident triage. Idempotency key ensures a retried
-- PagerDuty webhook doesn't cause duplicate triage runs.
-- =====================================================================
CREATE TABLE triage_runs (
    id                    TEXT         PRIMARY KEY,
    idempotency_key       TEXT         NOT NULL UNIQUE,
    incident_id           TEXT         NOT NULL,
    incident_url          TEXT,
    alert_summary         TEXT         NOT NULL,
    severity              TEXT         NOT NULL
                          CHECK (severity IN ('P0', 'P1', 'P2', 'P3', 'P4', 'INFO')),
    service               TEXT,
    status                TEXT         NOT NULL DEFAULT 'queued'
                          CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled')),
    overall_confidence    NUMERIC(4,3),
    aggregated_summary    TEXT,
    slack_channel         TEXT,
    slack_message_ts      TEXT,
    raw_payload           JSONB        NOT NULL,
    total_cost_usd        NUMERIC(10,6),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX triage_runs_incident_idx ON triage_runs (incident_id);
CREATE INDEX triage_runs_created_at_idx ON triage_runs (created_at DESC);
CREATE INDEX triage_runs_status_idx ON triage_runs (status);


-- =====================================================================
-- Findings — what each specialist produced
--
-- Cascades on triage delete so we don't accumulate orphaned findings
-- if we ever hard-delete a triage (rare, but safer).
-- =====================================================================
CREATE TABLE findings (
    id                TEXT         PRIMARY KEY,
    triage_id         TEXT         NOT NULL REFERENCES triage_runs(id) ON DELETE CASCADE,
    specialist        TEXT         NOT NULL
                      CHECK (specialist IN ('symptoms', 'change', 'metrics', 'comms', 'aggregator')),
    severity          TEXT         NOT NULL
                      CHECK (severity IN ('P0', 'P1', 'P2', 'P3', 'P4', 'INFO')),
    category          TEXT         NOT NULL
                      CHECK (category IN (
                          'deploy_regression', 'upstream_failure', 'capacity',
                          'data_quality', 'config_change', 'feature_flag',
                          'third_party_outage', 'unknown'
                      )),
    service           TEXT,
    evidence_ts       TIMESTAMPTZ,
    summary           TEXT         NOT NULL,
    rationale         TEXT         NOT NULL,
    evidence_url      TEXT,
    confidence        NUMERIC(4,3) NOT NULL
                      CHECK (confidence >= 0.0 AND confidence <= 1.0),
    agreement_count   INT          NOT NULL DEFAULT 1
                      CHECK (agreement_count >= 1),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX findings_triage_idx ON findings (triage_id);
CREATE INDEX findings_severity_idx ON findings (severity);


-- =====================================================================
-- HITL — human approval queue for suggested write-actions
-- =====================================================================
CREATE TABLE hitl_approvals (
    id                    TEXT         PRIMARY KEY,
    triage_id             TEXT         NOT NULL REFERENCES triage_runs(id) ON DELETE CASCADE,
    finding_id            TEXT         REFERENCES findings(id) ON DELETE SET NULL,
    suggested_action      JSONB        NOT NULL,
    action_description    TEXT         NOT NULL,
    outcome               TEXT         NOT NULL DEFAULT 'pending'
                          CHECK (outcome IN ('pending', 'approved', 'rejected', 'escalated', 'expired')),
    approved_by           TEXT,
    approved_at           TIMESTAMPTZ,
    rejection_reason      TEXT,
    expires_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX hitl_approvals_triage_idx ON hitl_approvals (triage_id);
CREATE INDEX hitl_approvals_outcome_idx ON hitl_approvals (outcome);


-- =====================================================================
-- Feedback — human corrections after the fact (fuels continuous learning)
-- =====================================================================
CREATE TABLE hitl_feedback (
    id                TEXT         PRIMARY KEY,
    triage_id         TEXT         NOT NULL REFERENCES triage_runs(id) ON DELETE CASCADE,
    finding_id        TEXT         REFERENCES findings(id) ON DELETE SET NULL,
    correctness       TEXT
                      CHECK (correctness IN ('correct', 'partially_correct', 'wrong')),
    correction_text   TEXT,
    submitted_by      TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX hitl_feedback_triage_idx ON hitl_feedback (triage_id);


-- =====================================================================
-- Lane 3 — Time: agent_events
--
-- The observability spine. Every action becomes one row here — span
-- starts, span ends, LLM calls, tool calls, decisions, escalations.
-- This single table powers three consumers: trace viewer, audit trail,
-- cost ledger.
--
-- Notice: NO foreign key to triage_runs. We deliberately break normalization
-- here — this table is high-volume and append-only, and a FK check on every
-- insert would slow the hot path without adding safety we care about. If a
-- triage is deleted, its events remain as a historical record.
-- =====================================================================
CREATE TABLE agent_events (
    id             TEXT         PRIMARY KEY,
    ts             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    triage_id      TEXT         NOT NULL,
    specialist     TEXT         NOT NULL
                   CHECK (specialist IN ('symptoms', 'change', 'metrics', 'comms', 'aggregator')),
    span_id        TEXT         NOT NULL,
    parent_span_id TEXT,
    event_type     TEXT         NOT NULL
                   CHECK (event_type IN (
                       'span.start', 'span.end', 'llm.call', 'tool.call',
                       'decision', 'escalation', 'error'
                   )),
    model          TEXT,
    tokens_in      INT,
    tokens_out     INT,
    cost_usd       NUMERIC(10,6),
    tool_name      TEXT,
    latency_ms     INT,
    outcome        TEXT,
    confidence     NUMERIC(4,3),
    payload        JSONB
);

CREATE INDEX agent_events_ts_brin_idx ON agent_events USING BRIN (ts);
CREATE INDEX agent_events_triage_ts_idx ON agent_events (triage_id, ts);