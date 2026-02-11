ALTER TABLE case_documents
ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ----------------------------------------------------------------------------
-- adding new status in enum
-- ----------------------------------------------------------------------------
ALTER TYPE document_ingestion_phase_enum
  ADD VALUE IF NOT EXISTS 'WAITING_FOR_UPLOAD';
