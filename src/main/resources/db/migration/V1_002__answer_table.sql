
CREATE TABLE IF NOT EXISTS answers (
    id UUID not null,
  case_id     UUID        NOT NULL,
  query_id    UUID        NOT NULL,
  version     INTEGER     NOT NULL,
  answer      TEXT        NOT NULL,
  llm_input   TEXT        NULL,
  doc_id      UUID        NULL, -- optional lineage to the document used for this answer
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (case_id, query_id, version),
  CONSTRAINT fk_ans_query FOREIGN KEY (query_id) REFERENCES queries (query_id) ON DELETE CASCADE,
  CONSTRAINT fk_ans_doc   FOREIGN KEY (doc_id)   REFERENCES case_documents (doc_id) ON DELETE SET NULL,
  CONSTRAINT ans_version_positive CHECK (version >= 1),
  CONSTRAINT ans_text_not_blank CHECK (length(btrim(answer)) > 0)
);

CREATE INDEX  ON answers (case_id, query_id, created_at DESC);
CREATE INDEX  idx_ans_case_query_ver_desc  ON answers (case_id, query_id, version DESC);