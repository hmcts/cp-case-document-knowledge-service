-- V1__initial_schema.sql
-- Initial schema (first-time install) for Case Documents AI Responses
-- Postgres 14+
-- Idempotent via IF NOT EXISTS; no ALTER/PL/pgSQL guards included.

BEGIN;

-- ===================================================================
-- Canonical queries table
-- ===================================================================
CREATE TABLE IF NOT EXISTS queries (
  query_id   UUID        PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE queries IS 'Canonical registry of query IDs.';

-- ===================================================================
-- Time-versioned query_versions (includes query_prompt and per-query status)
-- ===================================================================
CREATE TABLE IF NOT EXISTS query_versions (
  query_id     UUID        NOT NULL,
  effective_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  user_query   TEXT        NOT NULL,
  query_prompt TEXT,
  status       VARCHAR(32) NOT NULL DEFAULT 'UPLOADED'
    CHECK (status IN ('UPLOADED', 'INGESTED', 'ANSWERS_AVAILABLE')),
  PRIMARY KEY (query_id, effective_at),
  CONSTRAINT fk_qv_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE
);
COMMENT ON TABLE query_versions IS
  'Time-versioned user questions (snapshot by effective_at). Includes optional normalized prompt and per-query ingestion status.';

CREATE INDEX IF NOT EXISTS idx_qv_query_eff_desc
  ON query_versions (query_id, effective_at DESC);

CREATE INDEX IF NOT EXISTS idx_qv_status
  ON query_versions (status);

-- ===================================================================
-- Versioned answers – integer-versioned answers per query
-- ===================================================================
CREATE TABLE IF NOT EXISTS answers (
  query_id     UUID        NOT NULL,
  version      INTEGER     NOT NULL,
  answer       TEXT        NOT NULL,
  llm_input    TEXT,
  date_created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (query_id, version),
  CONSTRAINT fk_ans_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE
);
COMMENT ON TABLE answers IS 'Integer-versioned answers per query (diagnostic llm_input optional).';

CREATE INDEX IF NOT EXISTS idx_answers_query_version_desc
  ON answers (query_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_answers_query_date_desc
  ON answers (query_id, date_created DESC);

-- ===================================================================
-- Global ingestion_status_history (optional global timeline)
-- ===================================================================
CREATE TABLE IF NOT EXISTS ingestion_status_history (
  changed_at TIMESTAMPTZ PRIMARY KEY DEFAULT NOW(),
  status     VARCHAR(32) NOT NULL
    CHECK (status IN ('UPLOADED', 'INGESTED', 'ANSWERS_AVAILABLE'))
);
COMMENT ON TABLE ingestion_status_history IS 'Time-versioned ingestion/status states.';

CREATE INDEX IF NOT EXISTS idx_ish_changed_desc
  ON ingestion_status_history (changed_at DESC);


COMMIT;
