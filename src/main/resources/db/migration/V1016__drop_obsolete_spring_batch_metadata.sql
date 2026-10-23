-- DD-43866: Decommission obsolete Spring Batch metadata schema.
--
-- Spring Batch is not a dependency of this service (no spring-boot-starter-batch on the
-- classpath) and nothing writes to the BATCH_* tables created in
-- V1000__spring_batch_metadata.sql -- they are empty template residue. Flyway is
-- append-only, so V1000 is left untouched and the objects are dropped here.
-- Idempotent: safe where the objects are already absent.

-- Child tables first (FK dependents), then parents.
DROP TABLE IF EXISTS BATCH_STEP_EXECUTION_CONTEXT CASCADE;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_CONTEXT CASCADE;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_PARAMS CASCADE;
DROP TABLE IF EXISTS BATCH_STEP_EXECUTION CASCADE;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION CASCADE;
DROP TABLE IF EXISTS BATCH_JOB_INSTANCE CASCADE;

-- Sequences (no dependencies).
DROP SEQUENCE IF EXISTS BATCH_STEP_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_SEQ;
