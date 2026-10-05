-- Supports the DD-43185 stalled-document aggregate:
--   ingestion_phase IN (...) AND ingestion_phase_at < :cutoff, GROUP BY ingestion_phase.
-- Deliberately NOT partial: the monitored phase set is a requirements decision (ADR-004) and a
-- partial predicate would have to be re-migrated if it changes. A plain composite serves any
-- subset of phases, with literal or bound values, and needs no predicate-implication proof.
CREATE INDEX IF NOT EXISTS idx_cd_phase_phase_at
    ON case_documents (ingestion_phase, ingestion_phase_at);
COMMENT ON INDEX idx_cd_phase_phase_at IS
'DD-43185: serves the stalled-document monitoring aggregate (ingestion_phase + age). Supersedes idx_cd_phase as a prefix; idx_cd_phase deliberately retained -- dropping a shipped index is out of scope for DD-43185.';

-- Supports the DD-43185 queries-awaiting-answer aggregate:
--   status = 'ANSWER_NOT_AVAILABLE' AND status_at < :cutoff.
-- Partial: ANSWER_NOT_AVAILABLE is the initial status and rows leave it permanently, so this
-- index covers only the outstanding population and shrinks as answers land. The equality
-- predicate is trivially provable, so the planner will use it whenever the query spells the
-- status as a literal (which the native query does).
CREATE INDEX IF NOT EXISTS idx_cqs_awaiting_answer_at
    ON case_query_status (status_at)
    WHERE status = 'ANSWER_NOT_AVAILABLE';
COMMENT ON INDEX idx_cqs_awaiting_answer_at IS
'DD-43185: partial index serving the queries-awaiting-answer monitoring aggregate. Only used when the query filters status with the literal ''ANSWER_NOT_AVAILABLE''.';
