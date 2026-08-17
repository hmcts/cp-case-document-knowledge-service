-- ----------------------------------------------------------------------------
-- Persist the RAG-issued documentReference for the document-ingestion upload
-- transaction that produced this row (ingestion-side analogue of the answer
-- tables' rag_transaction_id, added in V1012).
-- Nullable, additive: rows written before this migration keep NULL, no backfill.
-- Stored verbatim as TEXT — no parse, no shape CHECK — see ADR-002 (DD-43083).
-- ----------------------------------------------------------------------------
ALTER TABLE case_documents
    ADD COLUMN IF NOT EXISTS rag_document_reference TEXT NULL;
COMMENT ON COLUMN case_documents.rag_document_reference IS
'RAG-issued documentReference returned by POST /document-upload — the ingestion-transaction correlation id for this document (ingestion-side analogue of answers.rag_transaction_id). Stored verbatim as received; nullable — NULL for rows in WAITING_FOR_UPLOAD, for uploads that never reached the UPLOADED transition, and for rows written before this column existed (not backfilled).';
