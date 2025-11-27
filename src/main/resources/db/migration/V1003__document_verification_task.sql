-- ============================================================================
-- V1002__document_verification_queue.sql
-- Case Documents Knowledge – Document Verification Queue (PostgreSQL 14+)
-- ============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'document_verification_status_enum') THEN
    CREATE TYPE document_verification_status_enum AS ENUM (
      'PENDING',
      'IN_PROGRESS',
      'SUCCEEDED',
      'FAILED'
    );
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS document_verification_task (
  id               BIGSERIAL                      PRIMARY KEY,
  doc_id           UUID                           NOT NULL,
  case_id          UUID                           NOT NULL,
  blob_name        TEXT                           NOT NULL,

  attempt_count    INTEGER                        NOT NULL DEFAULT 0,
  max_attempts     INTEGER                        NOT NULL,

  status           document_verification_status_enum NOT NULL DEFAULT 'PENDING',

  last_status      TEXT,
  last_reason      TEXT,
  last_status_ts   TIMESTAMPTZ,

  next_attempt_at  TIMESTAMPTZ                    NOT NULL DEFAULT NOW(),

  lock_owner       TEXT,
  lock_acquired_at TIMESTAMPTZ,

  created_at       TIMESTAMPTZ                    NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ                    NOT NULL DEFAULT NOW(),

  CONSTRAINT dvt_blob_name_not_blank CHECK (length(btrim(blob_name)) > 0),
  CONSTRAINT dvt_attempts_non_negative CHECK (attempt_count >= 0),
  CONSTRAINT dvt_max_attempts_positive CHECK (max_attempts >= 1)
);

COMMENT ON TABLE document_verification_task IS
'Queue of per-document ingestion verification tasks, processed by scheduler workers using SKIP LOCKED.';

CREATE INDEX IF NOT EXISTS idx_dvt_status_next_attempt
  ON document_verification_task (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_dvt_doc_id
  ON document_verification_task (doc_id);

CREATE INDEX IF NOT EXISTS idx_dvt_case_id
  ON document_verification_task (case_id);
