-- ============================================================================
-- V3: User, organization, membership, and invitation mirror tables
-- ============================================================================
-- Mirrors Keycloak user and organization data into the local IAM schema so
-- that UI queries, billing joins, and usage reports can read natively from
-- PostgreSQL instead of hitting the Keycloak Admin API.
--
-- Keycloak remains the source of truth for identity/auth. These tables are
-- populated by event projections (UserRegisteredEvent, OrganizationCreatedEvent,
-- etc.) and serve as the CQRS read model.
--
-- NOTE: No foreign key constraints are used here. These are read-model
-- projection tables populated asynchronously by event handlers. Events arrive
-- in orchestrator step order (e.g. OrganizationCreatedEvent before
-- TenantCreatedEvent), so FK constraints would cause insertion failures.
-- Referential integrity is guaranteed by the command-side orchestrators.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Users — mirrors Keycloak users + app-specific profile fields
-- ---------------------------------------------------------------------------
CREATE TABLE iam_user (
    user_id         UUID            PRIMARY KEY,                -- Keycloak sub claim (immutable)
    tenant_id       VARCHAR(255)    NOT NULL UNIQUE,            -- user's personal tenant
    username        VARCHAR(255)    NOT NULL UNIQUE,
    email           VARCHAR(500)    NOT NULL UNIQUE,
    full_name       VARCHAR(500),                               -- from Keycloak profile
    display_name    VARCHAR(500),                               -- app-specific override
    avatar_url      VARCHAR(2048),
    locale          VARCHAR(50),
    timezone        VARCHAR(100),
    status          VARCHAR(50)     NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'deleted')),
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,      -- mirrors Keycloak enabled flag
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_iam_user_status     ON iam_user(status);
CREATE INDEX idx_iam_user_created_at ON iam_user(created_at);
CREATE INDEX idx_iam_user_tenant     ON iam_user(tenant_id);

-- ---------------------------------------------------------------------------
-- 2. Organizations — mirrors Keycloak organizations
-- ---------------------------------------------------------------------------
CREATE TABLE iam_organization (
    org_id          VARCHAR(255)    PRIMARY KEY,                -- Keycloak org ID
    tenant_id       VARCHAR(255)    NOT NULL UNIQUE,            -- org's tenant record
    org_name        VARCHAR(500)    NOT NULL UNIQUE,            -- Keycloak org alias (= tenant_id value)
    display_name    VARCHAR(500),
    description     TEXT,
    status          VARCHAR(50)     NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'deleted')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_iam_org_tenant ON iam_organization(tenant_id);

-- ---------------------------------------------------------------------------
-- 3. Organization membership — links users to orgs
-- ---------------------------------------------------------------------------
CREATE TABLE org_membership (
    org_id          VARCHAR(255)    NOT NULL,
    user_id         UUID            NOT NULL,
    role            VARCHAR(100)    NOT NULL DEFAULT 'member'
        CHECK (role IN ('member', 'admin')),
    joined_at       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (org_id, user_id)
);

CREATE INDEX idx_membership_user ON org_membership(user_id);

-- ---------------------------------------------------------------------------
-- 4. Organization invitations — tracks pending invites for role pre-assignment
-- ---------------------------------------------------------------------------
CREATE TABLE org_invitation (
    invitation_id   UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          VARCHAR(255)    NOT NULL,
    email           VARCHAR(500)    NOT NULL,
    invited_by      UUID,
    intended_role   VARCHAR(100)    NOT NULL DEFAULT 'member'
        CHECK (intended_role IN ('member', 'admin')),
    status          VARCHAR(50)     NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'expired')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMPTZ
);

CREATE INDEX idx_invitation_org_status   ON org_invitation(org_id, status);
CREATE INDEX idx_invitation_email_status ON org_invitation(email, status);
