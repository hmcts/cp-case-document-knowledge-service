-- ----------------------------------------------------------------------------
-- Persist the RAG service transactionId that produced each answer version.
-- Nullable, additive: rows written before this migration keep NULL, no backfill.
-- ----------------------------------------------------------------------------
ALTER TABLE answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version (nullable — not backfilled for rows written before this column existed).';

ALTER TABLE case_level_latest_doc_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN case_level_latest_doc_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';

ALTER TABLE case_level_all_documents_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN case_level_all_documents_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';

ALTER TABLE defendant_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN defendant_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';
