-- ============================================================================
-- Case Documents Knowledge – Initial Schema (PostgreSQL 14+)
-- - Case-scoped ingestion pipeline (Azure Blob → ingest → answers)
-- - Canonical queries + versioned query definitions (user text + normalized prompt)
-- - Stable query label for UI (in queries)
-- - Per-(case, query) lifecycle status (ANSWER_NOT_AVAILABLE/ANSWER_AVAILABLE)
-- - Versioned answers with created_at (RFC3339 in API), optional captured llm_input
-- - Optional document lineage per case (supports re-uploads)
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- Enumerations (align with OpenAPI)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'query_lifecycle_status_enum') THEN
    CREATE TYPE query_lifecycle_status_enum AS ENUM ('ANSWER_NOT_AVAILABLE', 'ANSWER_AVAILABLE');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'document_ingestion_phase_enum') THEN
    CREATE TYPE document_ingestion_phase_enum AS ENUM ('NOT_FOUND','UPLOADING','UPLOADED','INGESTING','INGESTED','FAILED');
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- Canonical Queries (10 predefined IDs live here; definitions live in versions)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS queries (
  query_id   UUID        PRIMARY KEY,
  label      TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT queries_label_not_blank CHECK (length(btrim(label)) > 0)
);
COMMENT ON TABLE queries IS 'Canonical queries; stable UI label + created_at; definition text/prompt live in query_versions.';
-- Case-insensitive uniqueness for labels (stable names in UI)
CREATE UNIQUE INDEX IF NOT EXISTS ux_queries_label_ci ON queries (lower(btrim(label)));

-- Each new definition of a query is an immutable version chosen by effective_at.
CREATE TABLE IF NOT EXISTS query_versions (
  query_id     UUID        NOT NULL,
  effective_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  user_query   TEXT        NOT NULL,
  query_prompt TEXT        NOT NULL,
  PRIMARY KEY (query_id, effective_at),
  CONSTRAINT fk_qv_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
  CONSTRAINT qv_user_query_not_blank CHECK (length(btrim(user_query)) > 0),
  CONSTRAINT qv_query_prompt_not_blank CHECK (length(btrim(query_prompt)) > 0)
);
COMMENT ON TABLE query_versions IS 'Versioned query definitions (user text + normalized prompt).';
CREATE INDEX IF NOT EXISTS idx_qv_query_eff_desc ON query_versions (query_id, effective_at DESC);

-- Convenience view: latest definition per query (joins label for UI)
CREATE OR REPLACE VIEW v_query_definitions_latest AS
SELECT DISTINCT ON (q.query_id)
  q.query_id,
  q.label,
  qv.effective_at,
  qv.user_query,
  qv.query_prompt
FROM queries q
JOIN query_versions qv ON qv.query_id = q.query_id
ORDER BY q.query_id, qv.effective_at DESC;

COMMENT ON VIEW v_query_definitions_latest IS
'Latest definition per query (with label): query_id, label, effective_at, user_query, query_prompt.';

-- ----------------------------------------------------------------------------
-- Case Documents (optional lineage per upload; latest per case drives ingestion status)
-- ----------------------------------------------------------------------------
-- If you need gen_random_uuid() for doc_id default, enable pgcrypto.
-- CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS case_documents (
  doc_id             UUID        PRIMARY KEY, -- consider DEFAULT gen_random_uuid()
  case_id            UUID        NOT NULL,
  source             TEXT        NOT NULL DEFAULT 'IDPC',
  blob_uri           TEXT        NOT NULL,
  content_type       TEXT        NULL,
  size_bytes         BIGINT      NULL,
  sha256_hex         TEXT        NULL,
  uploaded_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ingestion_phase    document_ingestion_phase_enum NOT NULL DEFAULT 'UPLOADING',
  ingestion_phase_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT cd_blob_uri_not_blank CHECK (length(btrim(blob_uri)) > 0),
  CONSTRAINT cd_source_not_blank CHECK (length(btrim(source)) > 0),
  CONSTRAINT cd_size_nonneg CHECK (size_bytes IS NULL OR size_bytes >= 0),
  CONSTRAINT cd_sha256_shape CHECK (sha256_hex IS NULL OR sha256_hex ~ '^[0-9a-fA-F]{64}$')
);
COMMENT ON TABLE case_documents IS 'Per-case document uploads (Azure Blob lineage + ingestion phase).';
CREATE INDEX IF NOT EXISTS idx_cd_case_uploaded_desc ON case_documents (case_id, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_cd_case_phase         ON case_documents (case_id, ingestion_phase);
CREATE INDEX IF NOT EXISTS idx_cd_phase              ON case_documents (ingestion_phase);

-- Latest case-level ingestion snapshot view (used by /ingestions/status)
CREATE OR REPLACE VIEW v_case_ingestion_status AS
SELECT DISTINCT ON (case_id)
  case_id,
  ingestion_phase     AS phase,
  ingestion_phase_at  AS last_updated
FROM case_documents
ORDER BY case_id, ingestion_phase_at DESC;

COMMENT ON VIEW v_case_ingestion_status IS
'Latest ingestion phase per case (phase, last_updated).';

-- ----------------------------------------------------------------------------
-- Per-(case, query) lifecycle status (drives /queries?caseId=… listing)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS case_query_status (
  case_id             UUID                         NOT NULL,
  query_id            UUID                         NOT NULL,
  status              query_lifecycle_status_enum  NOT NULL DEFAULT 'ANSWER_NOT_AVAILABLE',
  status_at           TIMESTAMPTZ                  NOT NULL DEFAULT NOW(),
  doc_id              UUID                         NULL,  -- optional lineage to the document that produced this status
  last_answer_version INTEGER                      NULL,
  last_answer_at      TIMESTAMPTZ                  NULL,
  PRIMARY KEY (case_id, query_id),
  CONSTRAINT fk_cqs_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
  CONSTRAINT fk_cqs_doc   FOREIGN KEY (doc_id)   REFERENCES case_documents (doc_id) ON DELETE SET NULL
);
COMMENT ON TABLE case_query_status IS
'Lifecycle status of each canonical query for a given case (ANSWER_NOT_AVAILABLE/ANSWER_AVAILABLE).';
CREATE INDEX IF NOT EXISTS idx_cqs_case_status    ON case_query_status (case_id, status);
CREATE INDEX IF NOT EXISTS idx_cqs_status_at_desc ON case_query_status (status_at DESC);

-- ----------------------------------------------------------------------------
-- Answers (versioned per (case, query); aligns with OpenAPI AnswerResponse.version)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS answers (
  case_id     UUID        NOT NULL,
  query_id    UUID        NOT NULL,
  version     INTEGER     NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  answer      TEXT        NOT NULL,
  llm_input   TEXT        NULL,
  doc_id      UUID        NULL, -- optional lineage to the document used for this answer
  PRIMARY KEY (case_id, query_id, version),
  CONSTRAINT fk_ans_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
  CONSTRAINT fk_ans_doc   FOREIGN KEY (doc_id)   REFERENCES case_documents (doc_id) ON DELETE SET NULL,
  CONSTRAINT ans_version_positive CHECK (version >= 1),
  CONSTRAINT ans_text_not_blank CHECK (length(btrim(answer)) > 0)
);
COMMENT ON TABLE answers IS 'Versioned answers per (case, query) with optional captured LLM input.';
CREATE INDEX IF NOT EXISTS idx_ans_case_query_date_desc ON answers (case_id, query_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ans_case_query_ver_desc  ON answers (case_id, query_id, version DESC);

-- ----------------------------------------------------------------------------
-- Helper: allocate next answer version safely per (case, query) using advisory locks
-- Usage: INSERT ... (version) VALUES (next_answer_version(:caseId, :queryId))
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION next_answer_version(p_case_id UUID, p_query_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  v_next INTEGER;
  k1 INT;
  k2 INT;
BEGIN
  -- Build a stable lock on (case_id, query_id)
  SELECT
    ('x' || substr(md5(p_case_id::text), 1, 8))::bit(32)::int,
    ('x' || substr(md5(p_query_id::text), 1, 8))::bit(32)::int
  INTO k1, k2;

  PERFORM pg_advisory_xact_lock(k1, k2);

  SELECT COALESCE(MAX(a.version), 0) + 1
    INTO v_next
  FROM answers a
  WHERE a.case_id = p_case_id
    AND a.query_id = p_query_id;

  RETURN v_next;
END
$$;

COMMENT ON FUNCTION next_answer_version(UUID, UUID) IS
'Allocates the next per-(case,query) answer version under an advisory transaction lock.';

-- ----------------------------------------------------------------------------
-- Trigger: keep case_query_status pointers fresh on new answers
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_answers_after_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- Update existing status row
  UPDATE case_query_status
     SET status = 'ANSWER_AVAILABLE',
         status_at = COALESCE(NEW.created_at, NOW()),
         last_answer_version = NEW.version,
         last_answer_at = COALESCE(NEW.created_at, NOW()),
         doc_id = COALESCE(NEW.doc_id, doc_id)
   WHERE case_id = NEW.case_id
     AND query_id = NEW.query_id;

  -- If no row was updated, insert a new status row
  IF NOT FOUND THEN
    INSERT INTO case_query_status (case_id, query_id, status, status_at, doc_id, last_answer_version, last_answer_at)
    VALUES (NEW.case_id, NEW.query_id, 'ANSWER_AVAILABLE', COALESCE(NEW.created_at, NOW()), NEW.doc_id, NEW.version, COALESCE(NEW.created_at, NOW()))
    ON CONFLICT (case_id, query_id) DO UPDATE
      SET status = 'ANSWER_AVAILABLE',
          status_at = EXCLUDED.status_at,
          last_answer_version = EXCLUDED.last_answer_version,
          last_answer_at = EXCLUDED.last_answer_at,
          doc_id = COALESCE(EXCLUDED.doc_id, case_query_status.doc_id);
  END IF;

  RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS answers_after_insert ON answers;
CREATE TRIGGER answers_after_insert
AFTER INSERT ON answers
FOR EACH ROW
EXECUTE FUNCTION trg_answers_after_insert();

-- ----------------------------------------------------------------------------
-- Views
-- ----------------------------------------------------------------------------
-- Latest answer per (case, query)
CREATE OR REPLACE VIEW v_latest_answers AS
SELECT DISTINCT ON (case_id, query_id)
  case_id, query_id, version, created_at, answer, llm_input, doc_id
FROM answers
ORDER BY case_id, query_id, version DESC;

COMMENT ON VIEW v_latest_answers IS 'Latest answer version per (case, query).';

COMMIT;
