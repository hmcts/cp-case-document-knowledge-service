-- ----------------------------------------------------------------------------
-- adding new status EXCEEDED_FILE_SIZE_LIMIT in enum
-- ----------------------------------------------------------------------------
ALTER TYPE document_ingestion_phase_enum
  ADD VALUE IF NOT EXISTS 'EXCEEDED_FILE_SIZE_LIMIT';
