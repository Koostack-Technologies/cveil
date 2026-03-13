-- Allow 'created' status for newly registered tenants pending infrastructure provisioning.
-- Allow NULL schema_prefix until the tenant-manager completes provisioning.

ALTER TABLE platform_core.tenant ALTER COLUMN schema_prefix DROP NOT NULL;

-- Replace the inline CHECK constraint with one that includes 'created'.
-- Postgres auto-names the inline check; drop by scanning pg_constraint.
DO $$
DECLARE
    _cname text;
BEGIN
    SELECT c.conname INTO _cname
    FROM pg_constraint c
    JOIN pg_class r ON c.conrelid = r.oid
    JOIN pg_namespace n ON r.relnamespace = n.oid
    WHERE n.nspname = 'platform_core'
      AND r.relname = 'tenant'
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) LIKE '%status%';
    IF _cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE platform_core.tenant DROP CONSTRAINT ' || _cname;
    END IF;
END $$;

ALTER TABLE platform_core.tenant
    ADD CONSTRAINT tenant_status_check
        CHECK (status IN ('created', 'active', 'suspended', 'deleted', 'past_due'));
