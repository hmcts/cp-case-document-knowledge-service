-- ============================================================================
-- V1001__job_manager_jobs.sql
-- Job Manager – Jobs Table (PostgreSQL 14+)
-- ============================================================================

CREATE TABLE IF NOT EXISTS jobs (
  job_id                     UUID        PRIMARY KEY,
  worker_id                  UUID,

  worker_lock_time           TIMESTAMPTZ,

  assigned_task_name         TEXT,
  assigned_task_start_time  TIMESTAMPTZ,

  job_data                   TEXT        NOT NULL,

  retry_attempts_remaining   INTEGER     NOT NULL DEFAULT 0,
  priority                   INTEGER     NOT NULL DEFAULT 10
);

COMMENT ON TABLE jobs IS
'Job execution table for scheduler workers. Tracks assignment, locking, retries, and priority.';
