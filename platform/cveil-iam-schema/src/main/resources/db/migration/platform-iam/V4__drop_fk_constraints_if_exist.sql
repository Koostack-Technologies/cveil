-- ============================================================================
-- V4: Drop FK constraints if they were applied by the original V3 migration
-- ============================================================================
-- The V3 migration originally included FK constraints on the IAM mirror tables.
-- These caused IntegrityConstraintViolation errors because orchestrator sagas
-- publish events in an order where child rows (e.g. iam_organization) are
-- projected before parent rows (e.g. tenant) exist.
--
-- V3 has since been updated to not create FK constraints for fresh databases.
-- This V4 handles databases that already ran the original V3 with FKs.
-- ============================================================================

DO $$
BEGIN
    -- iam_user → tenant
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_iam_user_tenant'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE iam_user DROP CONSTRAINT fk_iam_user_tenant;
    END IF;

    -- iam_organization → tenant
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_iam_org_tenant'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE iam_organization DROP CONSTRAINT fk_iam_org_tenant;
    END IF;

    -- org_membership → iam_organization
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_membership_org'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE org_membership DROP CONSTRAINT fk_membership_org;
    END IF;

    -- org_membership → iam_user
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_membership_user'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE org_membership DROP CONSTRAINT fk_membership_user;
    END IF;

    -- org_invitation → iam_organization
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_invitation_org'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE org_invitation DROP CONSTRAINT fk_invitation_org;
    END IF;

    -- org_invitation → iam_user (invited_by)
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'fk_invitation_inviter'
                 AND table_schema = 'cveiliam') THEN
        ALTER TABLE org_invitation DROP CONSTRAINT fk_invitation_inviter;
    END IF;
END $$;
