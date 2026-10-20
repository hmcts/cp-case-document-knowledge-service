-- ----------------------------------------------------------------------------
-- DD-43822/DD-43848: give query_versions an explicit, ordered version label
-- (V1, V2, ...) per query_id, and persist which prompt version produced each
-- generated answer.
-- ----------------------------------------------------------------------------

-- query_versions: add the label and backfill every existing row so the
-- current "latest" row for every query gets a correct label immediately,
-- not only once that query's prompt is next edited.
ALTER TABLE query_versions
    ADD COLUMN IF NOT EXISTS version_label TEXT;

UPDATE query_versions v
   SET version_label = 'V' || sub.rn
  FROM (
    SELECT query_id, effective_at,
           ROW_NUMBER() OVER (PARTITION BY query_id ORDER BY effective_at ASC) AS rn
      FROM query_versions
  ) sub
 WHERE v.query_id = sub.query_id
   AND v.effective_at = sub.effective_at
   AND v.version_label IS NULL;

ALTER TABLE query_versions
    ALTER COLUMN version_label SET NOT NULL;
COMMENT ON COLUMN query_versions.version_label IS
'Ordered version label per query_id (V1, V2, ...), backfilled by effective_at ascending; new rows compute the next label in QueryService.upsertDefinitions.';

-- Answer tables: record which query_prompt_version (the label above) was
-- actually used to generate each answer. Nullable, additive: rows written
-- before this migration keep NULL, no backfill (nothing to derive it from).
ALTER TABLE answers
    ADD COLUMN IF NOT EXISTS query_prompt_version TEXT NULL;
COMMENT ON COLUMN answers.query_prompt_version IS
'query_versions.version_label used to generate this answer (nullable — not backfilled for rows written before this column existed). Distinct from the existing version column, which is an answer-regeneration counter, not a prompt version.';

ALTER TABLE case_level_latest_doc_answers
    ADD COLUMN IF NOT EXISTS query_prompt_version TEXT NULL;
COMMENT ON COLUMN case_level_latest_doc_answers.query_prompt_version IS
'query_versions.version_label used to generate this answer.';

ALTER TABLE case_level_all_documents_answers
    ADD COLUMN IF NOT EXISTS query_prompt_version TEXT NULL;
COMMENT ON COLUMN case_level_all_documents_answers.query_prompt_version IS
'query_versions.version_label used to generate this answer.';

ALTER TABLE defendant_answers
    ADD COLUMN IF NOT EXISTS query_prompt_version TEXT NULL;
COMMENT ON COLUMN defendant_answers.query_prompt_version IS
'query_versions.version_label used to generate this answer.';

-- Surface the label on the latest-definition view used to build the RAG request.
-- CREATE OR REPLACE VIEW can only append columns at the end of the SELECT list, not insert or
-- reorder one in the middle (Postgres: "cannot change name of view column"). V1006 already
-- extended this view with a 6th column (qv.level) - version_label is appended as the 7th.
CREATE OR REPLACE VIEW v_query_definitions_latest AS
SELECT DISTINCT ON (q.query_id)
  q.query_id,
  q.label,
  qv.effective_at,
  qv.user_query,
  qv.query_prompt,
  qv.level,
  qv.version_label
FROM queries q
JOIN query_versions qv ON qv.query_id = q.query_id
ORDER BY q.query_id, qv.effective_at DESC;

COMMENT ON VIEW v_query_definitions_latest IS
'Latest definition per query (with label and version_label): query_id, label, effective_at, user_query, query_prompt, level, version_label.';
