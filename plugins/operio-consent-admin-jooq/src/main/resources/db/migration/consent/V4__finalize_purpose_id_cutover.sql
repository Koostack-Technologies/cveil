-- ============================================================================
-- V4: Finalize purpose_id cutover — drop purpose_name, enforce NOT NULL
-- ============================================================================
-- Destructive migration — removes the purpose_name column from consent_policy
-- and makes purpose_id NOT NULL. Only deploy after V3 is fully applied and
-- all purpose_id values are confirmed populated.
--
-- The unique constraints are widened from:
--     (subject_id, field_id, purpose_name)    → (subject_id, field_id, purpose_id)
--     (subject_id, category_id, purpose_name) → (subject_id, category_id, purpose_id)
--
-- Compatible with H2 (build-time jOOQ codegen) and PostgreSQL (production).
--
-- IMPORTANT: Constraints that reference purpose_name MUST be dropped before
-- the column itself is dropped. H2 enforces this ordering strictly.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Drop the old unique constraints that included purpose_name.
--    These were created by V2 with explicit names. Must happen before the
--    column drop so H2 does not reject the ALTER TABLE.
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy DROP CONSTRAINT IF EXISTS uq_consent_policy_subject_field_purpose;
ALTER TABLE consent_policy DROP CONSTRAINT IF EXISTS uq_consent_policy_subject_category_purpose;

-- ----------------------------------------------------------------------------
-- 2. Drop the old purpose_name column (all code now uses purpose_id)
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy DROP COLUMN purpose_name;

-- ----------------------------------------------------------------------------
-- 3. Make purpose_id NOT NULL now that all rows are backfilled
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy ALTER COLUMN purpose_id SET NOT NULL;

-- ----------------------------------------------------------------------------
-- 4. Recreate unique constraints using purpose_id instead of purpose_name
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy ADD CONSTRAINT uq_consent_field_purpose
    UNIQUE (subject_id, field_id, purpose_id);

ALTER TABLE consent_policy ADD CONSTRAINT uq_consent_category_purpose
    UNIQUE (subject_id, category_id, purpose_id);
