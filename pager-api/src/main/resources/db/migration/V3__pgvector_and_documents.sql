-- Enable the pgvector extension so we can store and query vectors.
-- The postgres container image needs to be pgvector-enabled — see
-- the docker-compose update in this PR.
CREATE EXTENSION IF NOT EXISTS vector;

-- Documents corpus: runbooks, past post-mortems, service READMEs, any
-- text the specialists should be able to retrieve from.
--
-- Deliberately narrow schema — content + metadata as JSON. If we ever
-- need structured queries beyond "find similar", we'll add columns then.
CREATE TABLE documents (
    id          TEXT PRIMARY KEY,
    kind        TEXT NOT NULL,          -- 'runbook', 'postmortem', 'readme'
    title       TEXT NOT NULL,
    content     TEXT NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT documents_kind_check CHECK (kind IN ('runbook', 'postmortem', 'readme'))
);

-- Full-text search column — a generated column so we don't have to
-- update it manually. English config is fine for our English corpus;
-- for multilingual we'd use a language column.
ALTER TABLE documents
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || content)) STORED;

CREATE INDEX idx_documents_content_tsv ON documents USING gin(content_tsv);
CREATE INDEX idx_documents_kind ON documents(kind);

-- Embeddings — one row per (document, embedding_model). Separate table
-- because a document may be re-embedded with a new model without
-- destroying the original. 384 dims matches all-MiniLM-L6-v2.
--
-- If we ever swap to OpenAI's text-embedding-3-small (1536 dims), we
-- add a new column or a new table rather than migrating in place.
CREATE TABLE document_embeddings (
    id            TEXT PRIMARY KEY,
    document_id   TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    model         TEXT NOT NULL,
    embedding     vector(384) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, model)
);

-- HNSW index for fast approximate nearest-neighbor search. Cosine
-- distance because our embedder returns L2-normalized vectors —
-- cosine and dot-product are equivalent for normalized vectors,
-- but pgvector's cosine operator is the semantically correct choice.
--
-- ef_construction=64 is a reasonable default. If we ingest millions of
-- documents, tuning this matters; for our small corpus it doesn't.
CREATE INDEX idx_document_embeddings_hnsw
    ON document_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);