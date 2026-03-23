DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'query_level_enum') THEN
    CREATE TYPE query_level_enum AS ENUM ('CASE', 'CASE_ALL_DOCUMENTS','DEFENDANT');
  END IF;
END;
 $$;


-- ----------------------------------------------------------------------------
-- Add level column to query_versions
-- ----------------------------------------------------------------------------
ALTER TABLE query_versions
ADD COLUMN IF NOT EXISTS level query_level_enum;

-- Backfill existing rows
UPDATE query_versions
SET level = 'CASE'
WHERE level IS NULL;

-- Enforce NOT NULL
--ALTER TABLE query_versions
--ALTER COLUMN level SET NOT NULL;


-- Update view to include level column
CREATE OR REPLACE VIEW v_query_definitions_latest AS
SELECT DISTINCT ON (q.query_id)
  q.query_id,
  q.label,
  qv.effective_at,
  qv.user_query,
  qv.query_prompt,
  qv.level
FROM queries q
JOIN query_versions qv ON qv.query_id = q.query_id
ORDER BY q.query_id, qv.effective_at DESC;

COMMENT ON VIEW v_query_definitions_latest IS
'Latest definition per query (with label): query_id, label, effective_at, user_query, query_prompt, level.';


-- ----------------------------------------------------------------------------
-- 2) Answer tables
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS case_level_latest_doc_answers (
    case_id UUID NOT NULL,
    query_id UUID NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answer TEXT NOT NULL,
    llm_input TEXT NULL,
    doc_id UUID NULL,
    PRIMARY KEY (case_id, query_id, version),
    CONSTRAINT fk_cllda_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
    CONSTRAINT fk_cllda_doc FOREIGN KEY (doc_id) REFERENCES case_documents (doc_id) ON DELETE SET NULL
);
CREATE INDEX idx_cllda_case_query_date_desc ON case_level_latest_doc_answers (case_id, query_id, created_at DESC);
CREATE INDEX idx_cllda_case_query_ver_desc  ON case_level_latest_doc_answers (case_id, query_id, version DESC);

CREATE TABLE IF NOT EXISTS case_level_all_documents_answers (
    case_id UUID NOT NULL,
    query_id UUID NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answer TEXT NOT NULL,
    llm_input TEXT NULL,
    PRIMARY KEY (case_id, query_id, version),
    CONSTRAINT fk_clada_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE
);
CREATE INDEX idx_clada_case_query_date_desc ON case_level_all_documents_answers (case_id, query_id, created_at DESC);
CREATE INDEX idx_clada_case_query_ver_desc  ON case_level_all_documents_answers (case_id, query_id, version DESC);

CREATE TABLE IF NOT EXISTS defendant_answers (
    case_id UUID NOT NULL,
    query_id UUID NOT NULL,
    defendant_id UUID NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answer TEXT NOT NULL,
    llm_input TEXT NULL,
    doc_id UUID NULL,
    PRIMARY KEY (case_id, query_id, defendant_id, version),
    CONSTRAINT fk_def_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
    CONSTRAINT fk_def_doc FOREIGN KEY (doc_id) REFERENCES case_documents (doc_id) ON DELETE SET NULL
);
CREATE INDEX idx_def_case_query_date_desc ON defendant_answers (case_id, query_id, created_at DESC);
CREATE INDEX idx_def_case_query_ver_desc  ON defendant_answers (case_id, query_id, version DESC);
CREATE INDEX idx_def_case_query_defendant ON defendant_answers (case_id, query_id, defendant_id);


-- ----------------------------------------------------------------------------
-- 4) Triggers to update case_query_status
-- ----------------------------------------------------------------------------
-- Trigger function for case_level_latest_doc_answers
CREATE OR REPLACE FUNCTION trg_cllda_after_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE case_query_status
     SET status = 'ANSWER_AVAILABLE',
         status_at = COALESCE(NEW.created_at, NOW()),
         last_answer_version = NEW.version,
         last_answer_at = COALESCE(NEW.created_at, NOW()),
         doc_id = COALESCE(NEW.doc_id, doc_id)
   WHERE case_id = NEW.case_id
     AND query_id = NEW.query_id;

  IF NOT FOUND THEN
    INSERT INTO case_query_status (case_id, query_id, status, status_at, last_answer_version, last_answer_at, doc_id)
    VALUES (NEW.case_id, NEW.query_id, 'ANSWER_AVAILABLE', COALESCE(NEW.created_at, NOW()), NEW.version, COALESCE(NEW.created_at, NOW()), NEW.doc_id)
    ON CONFLICT (case_id, query_id) DO UPDATE
      SET status = 'ANSWER_AVAILABLE',
          status_at = EXCLUDED.status_at,
          last_answer_version = EXCLUDED.last_answer_version,
          last_answer_at = EXCLUDED.last_answer_at,
          doc_id = COALESCE(EXCLUDED.doc_id, case_query_status.doc_id);
  END IF;
  RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS cllda_after_insert ON case_level_latest_doc_answers;
CREATE TRIGGER cllda_after_insert
AFTER INSERT ON case_level_latest_doc_answers
FOR EACH ROW
EXECUTE FUNCTION trg_cllda_after_insert();

-- Trigger function for case_level_all_documents_answers
CREATE OR REPLACE FUNCTION trg_clada_after_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE case_query_status
     SET status = 'ANSWER_AVAILABLE',
         status_at = COALESCE(NEW.created_at, NOW()),
         last_answer_version = NEW.version,
         last_answer_at = COALESCE(NEW.created_at, NOW())
   WHERE case_id = NEW.case_id
     AND query_id = NEW.query_id;

  IF NOT FOUND THEN
    INSERT INTO case_query_status (case_id, query_id, status, status_at, last_answer_version, last_answer_at)
    VALUES (NEW.case_id, NEW.query_id, 'ANSWER_AVAILABLE', COALESCE(NEW.created_at, NOW()), NEW.version, COALESCE(NEW.created_at, NOW()))
    ON CONFLICT (case_id, query_id) DO UPDATE
      SET status = 'ANSWER_AVAILABLE',
          status_at = EXCLUDED.status_at,
          last_answer_version = EXCLUDED.last_answer_version,
          last_answer_at = EXCLUDED.last_answer_at;
  END IF;
  RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS clada_after_insert ON case_level_all_documents_answers;
CREATE TRIGGER clada_after_insert
AFTER INSERT ON case_level_all_documents_answers
FOR EACH ROW
EXECUTE FUNCTION trg_clada_after_insert();

-- Trigger function for defendant_answers
CREATE OR REPLACE FUNCTION trg_def_after_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE case_query_status
     SET status = 'ANSWER_AVAILABLE',
         status_at = COALESCE(NEW.created_at, NOW()),
         last_answer_version = NEW.version,
         last_answer_at = COALESCE(NEW.created_at, NOW()),
         doc_id = COALESCE(NEW.doc_id, doc_id)
   WHERE case_id = NEW.case_id
     AND query_id = NEW.query_id;

  IF NOT FOUND THEN
    INSERT INTO case_query_status (case_id, query_id, status, status_at, last_answer_version, last_answer_at, doc_id)
    VALUES (NEW.case_id, NEW.query_id, 'ANSWER_AVAILABLE', COALESCE(NEW.created_at, NOW()), NEW.version, COALESCE(NEW.created_at, NOW()), NEW.doc_id)
    ON CONFLICT (case_id, query_id) DO UPDATE
      SET status = 'ANSWER_AVAILABLE',
          status_at = EXCLUDED.status_at,
          last_answer_version = EXCLUDED.last_answer_version,
          last_answer_at = EXCLUDED.last_answer_at,
          doc_id = COALESCE(EXCLUDED.doc_id, case_query_status.doc_id);
  END IF;
  RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS def_after_insert ON defendant_answers;
CREATE TRIGGER def_after_insert
AFTER INSERT ON defendant_answers
FOR EACH ROW
EXECUTE FUNCTION trg_def_after_insert();