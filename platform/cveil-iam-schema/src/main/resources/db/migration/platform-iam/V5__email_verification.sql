-- ============================================================================
-- V5: Email verification tracking
-- ============================================================================
-- Adds email verification columns to the iam_user read model. Keycloak handles
-- the actual verification flow (VERIFY_EMAIL required action), and these columns
-- mirror that state so queries can filter/display verification status without
-- hitting the Keycloak Admin API.
--
-- Verification status is synced from Keycloak on login and updated via the
-- EMAIL_VERIFIED event projection.
-- ============================================================================

ALTER TABLE iam_user
    ADD COLUMN email_verified       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at    TIMESTAMPTZ;

CREATE INDEX idx_iam_user_email_verified ON iam_user(email_verified);
