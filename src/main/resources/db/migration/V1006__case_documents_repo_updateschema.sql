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

