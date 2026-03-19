-- V5: Harden DSAR tables with constraints, FK, and timezone-aware timestamps.

-- Add FK from activity log to request table
ALTER TABLE dsar_activity_log
    ADD CONSTRAINT fk_dsar_activity_request
    FOREIGN KEY (request_uuid) REFERENCES dsar_request(dsar_request_uuid)
    ON DELETE CASCADE;

-- Add CHECK constraints for valid status values
ALTER TABLE dsar_request
    ADD CONSTRAINT ck_dsar_status
    CHECK (status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'IN_PROGRESS',
                      'COMPLETED', 'REJECTED', 'CANCELLED'));

-- Add CHECK constraints for valid request types
ALTER TABLE dsar_request
    ADD CONSTRAINT ck_dsar_request_type
    CHECK (request_type IN ('ACCESS', 'DELETION', 'RECTIFICATION',
                            'PORTABILITY', 'RESTRICTION', 'OBJECTION'));

-- Migrate timestamps to timezone-aware (safe for existing data).
-- Uses TIMESTAMP WITH TIME ZONE for H2 compatibility (H2 does not support the
-- PostgreSQL shorthand TIMESTAMPTZ).  The USING clause is PG-only, so we use
-- separate per-column statements that work on both H2 and PostgreSQL.
ALTER TABLE dsar_request ALTER COLUMN fulfilled_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE dsar_request ALTER COLUMN due_date     SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE dsar_request ALTER COLUMN created_at   SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE dsar_request ALTER COLUMN updated_at   SET DATA TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE dsar_activity_log ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
