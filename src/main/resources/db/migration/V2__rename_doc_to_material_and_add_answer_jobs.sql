BEGIN;

-- Enable UUID default generator if needed
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Rename doc_id -> material_id
ALTER TABLE case_documents RENAME COLUMN doc_id TO material_id;

-- case_query_status: rename + fk
ALTER TABLE case_query_status RENAME COLUMN doc_id TO material_id;
ALTER TABLE case_query_status DROP CONSTRAINT IF EXISTS fk_cqs_doc;
ALTER TABLE case_query_status
  ADD CONSTRAINT fk_cqs_material
  FOREIGN KEY (material_id) REFERENCES case_documents (material_id) ON DELETE SET NULL;

-- answers: rename + fk
ALTER TABLE answers RENAME COLUMN doc_id TO material_id;
ALTER TABLE answers DROP CONSTRAINT IF EXISTS fk_ans_doc;
ALTER TABLE answers
  ADD CONSTRAINT fk_ans_material
  FOREIGN KEY (material_id) REFERENCES case_documents (material_id) ON DELETE SET NULL;

-- Replace view v_latest_answers to reflect material_id
DROP VIEW IF EXISTS v_latest_answers;
CREATE OR REPLACE VIEW v_latest_answers AS
SELECT DISTINCT ON (case_id, query_id)
  case_id, query_id, version, created_at, answer, llm_input, material_id
FROM answers
ORDER BY case_id, query_id, version DESC;

COMMENT ON VIEW v_latest_answers IS 'Latest answer version per (case, query).';

-- Replace trigger function to use material_id
DROP TRIGGER IF EXISTS answers_after_insert ON answers;
DROP FUNCTION IF EXISTS trg_answers_after_insert();

CREATE OR REPLACE FUNCTION trg_answers_after_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE case_query_status
     SET status = 'ANSWER_AVAILABLE',
         status_at = COALESCE(NEW.created_at, NOW()),
         last_answer_version = NEW.version,
         last_answer_at = COALESCE(NEW.created_at, NOW()),
         material_id = COALESCE(NEW.material_id, material_id)
   WHERE case_id = NEW.case_id
     AND query_id = NEW.query_id;

  IF NOT FOUND THEN
    INSERT INTO case_query_status (case_id, query_id, status, status_at, material_id, last_answer_version, last_answer_at)
    VALUES (NEW.case_id, NEW.query_id, 'ANSWER_AVAILABLE', COALESCE(NEW.created_at, NOW()), NEW.material_id, NEW.version, COALESCE(NEW.created_at, NOW()))
    ON CONFLICT (case_id, query_id) DO UPDATE
      SET status = 'ANSWER_AVAILABLE',
          status_at = EXCLUDED.status_at,
          last_answer_version = EXCLUDED.last_answer_version,
          last_answer_at = EXCLUDED.last_answer_at,
          material_id = COALESCE(EXCLUDED.material_id, case_query_status.material_id);
  END IF;

  RETURN NULL;
END
$$;

CREATE TRIGGER answers_after_insert
AFTER INSERT ON answers
FOR EACH ROW
EXECUTE FUNCTION trg_answers_after_insert();

-- New table: answer_jobs (idempotent queue per material/version)
CREATE TABLE IF NOT EXISTS answer_jobs (
  job_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id      UUID        NOT NULL,
  query_id     UUID        NOT NULL,
  material_id  UUID        NOT NULL,
  version      INTEGER     NOT NULL,
  status       TEXT        NOT NULL DEFAULT 'NEW',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_answer_jobs UNIQUE (case_id, query_id, material_id),
  CONSTRAINT fk_aj_query     FOREIGN KEY (query_id)    REFERENCES queries (query_id)       ON DELETE CASCADE,
  CONSTRAINT fk_aj_material  FOREIGN KEY (material_id) REFERENCES case_documents (material_id) ON DELETE CASCADE,
  CONSTRAINT aj_version_positive CHECK (version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_aj_status       ON answer_jobs (status);
CREATE INDEX IF NOT EXISTS idx_aj_case_query   ON answer_jobs (case_id, query_id);
CREATE INDEX IF NOT EXISTS idx_aj_material     ON answer_jobs (material_id);

COMMIT;
