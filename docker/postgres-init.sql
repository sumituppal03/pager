-- Enable pgvector so JPA + the retrieval layer (PR #11) can use it.
CREATE EXTENSION IF NOT EXISTS vector;

-- Handy for id generation if we ever want it in SQL rather than app-side.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
