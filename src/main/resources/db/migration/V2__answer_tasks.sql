CREATE TABLE IF NOT EXISTS answer_tasks (
  case_id    UUID NOT NULL,
  query_id   UUID NOT NULL,
  status     TEXT NOT NULL DEFAULT 'NEW',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error TEXT NULL,
  PRIMARY KEY (case_id, query_id)
);
CREATE INDEX IF NOT EXISTS idx_answer_tasks_status ON answer_tasks(status);
