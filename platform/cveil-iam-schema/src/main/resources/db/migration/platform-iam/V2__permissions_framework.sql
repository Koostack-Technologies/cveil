-- ============================================================================
-- V2: Permissions framework for Cveil RBAC
-- ============================================================================
-- Replaces Keycloak-based role enforcement with an Cveil-managed permissions
-- framework. Introduces four tables:
--
--   permission       — atomic actions (e.g. 'consent:grant', 'catalogue:read')
--   role_definition  — system + custom org roles
--   role_permission  — which permissions each role grants
--   role_assignment  — user → role, scoped per tenant (personal or org)
--
-- Role assignments are per-tenant: the same user can have different roles in
-- their personal tenant vs. each organization they belong to.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Permissions — atomic actions that can be checked in resource methods
-- ---------------------------------------------------------------------------
CREATE TABLE platform_iam.permission (
    permission_id   VARCHAR(100)  PRIMARY KEY,
    domain          VARCHAR(50)   NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 2. Role definitions — system roles + custom per-org roles
-- ---------------------------------------------------------------------------
CREATE TABLE platform_iam.role_definition (
    role_id         VARCHAR(100)  PRIMARY KEY,
    tenant_id       VARCHAR(255),              -- NULL = system role, non-null = custom org role
    scope           VARCHAR(50)   NOT NULL CHECK (scope IN ('platform', 'tenant')),
    display_name    VARCHAR(200)  NOT NULL,
    description     TEXT,
    is_system       BOOLEAN       NOT NULL DEFAULT FALSE,
    is_assignable   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_role_definition_tenant ON platform_iam.role_definition(tenant_id);

-- ---------------------------------------------------------------------------
-- 3. Role → permission mapping
-- ---------------------------------------------------------------------------
CREATE TABLE platform_iam.role_permission (
    role_id         VARCHAR(100)  NOT NULL REFERENCES platform_iam.role_definition(role_id) ON DELETE CASCADE,
    permission_id   VARCHAR(100)  NOT NULL REFERENCES platform_iam.permission(permission_id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ---------------------------------------------------------------------------
-- 4. User → role assignment, scoped per tenant
-- ---------------------------------------------------------------------------
CREATE TABLE platform_iam.role_assignment (
    user_id         VARCHAR(255)  NOT NULL,
    tenant_id       VARCHAR(255),              -- NULL = platform-scope assignment
    role_id         VARCHAR(100)  NOT NULL REFERENCES platform_iam.role_definition(role_id) ON DELETE CASCADE,
    granted_by      VARCHAR(255)  NOT NULL,
    granted_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Unique constraint using COALESCE to treat NULL tenant_id as '' for uniqueness
CREATE UNIQUE INDEX idx_role_assignment_unique
    ON platform_iam.role_assignment (user_id, COALESCE(tenant_id, ''), role_id);

CREATE INDEX idx_role_assignment_tenant ON platform_iam.role_assignment(tenant_id, user_id);
CREATE INDEX idx_role_assignment_user   ON platform_iam.role_assignment(user_id);

-- ============================================================================
-- Seed: permissions
-- ============================================================================
INSERT INTO platform_iam.permission (permission_id, domain, description) VALUES
    -- Consent domain
    ('consent:grant',       'consent',    'Grant consent (single + batch)'),
    ('consent:revoke',      'consent',    'Revoke consent (single + batch)'),
    ('consent:evaluate',    'consent',    'Evaluate consent for a subject'),
    ('consent:read',        'consent',    'Read subject summary and history'),
    ('consent:export',      'consent',    'Export subject consent data'),
    ('consent:dsar',        'consent',    'Execute digital shredding / DSAR audit deletion'),
    ('consent:graph:read',  'consent',    'Read consent decision graph'),
    -- Catalogue domain
    ('catalogue:read',      'catalogue',  'List and view fields, entities, categories'),
    ('catalogue:write',     'catalogue',  'Create, update, and delete fields, entities, categories'),
    -- Governance domain
    ('governance:read',     'governance', 'List and view purposes, regulations, jurisdictions'),
    ('governance:write',    'governance', 'Create, update, and delete purposes, regulations, jurisdictions'),
    -- Events domain
    ('events:read',         'events',     'Read invalidation and audit events'),
    -- Organization management
    ('org:read',            'iam',        'View organization details and list members'),
    ('org:write',           'iam',        'Update organization metadata'),
    ('org:member:manage',   'iam',        'Add, remove, and invite organization members'),
    ('org:role:manage',     'iam',        'Assign and revoke roles for organization members'),
    -- Platform IAM (super-admin only)
    ('iam:user:read',       'iam',        'List and view platform users'),
    ('iam:user:manage',     'iam',        'Suspend, activate users and assign platform roles'),
    -- Platform tenant management
    ('platform:tenant:read',   'platform', 'List and view tenants'),
    ('platform:tenant:manage', 'platform', 'Create, delete tenants and change plans');

-- ============================================================================
-- Seed: system role definitions
-- ============================================================================
INSERT INTO platform_iam.role_definition (role_id, tenant_id, scope, display_name, description, is_system, is_assignable) VALUES
    ('super-admin',       NULL, 'platform', 'Super Administrator',  'Full platform access — all permissions on all tenants',         TRUE, FALSE),
    ('tenant-admin',      NULL, 'tenant',   'Tenant Admin',         'Full access within a tenant including org management',           TRUE, TRUE),
    ('tenant-write',      NULL, 'tenant',   'Tenant Writer',        'Read and write access to consent, catalogue, and governance',    TRUE, TRUE),
    ('tenant-read',       NULL, 'tenant',   'Tenant Reader',        'Read-only access to consent, catalogue, and governance',         TRUE, TRUE),
    ('consent-operator',  NULL, 'tenant',   'Consent Operator',     'Grant, revoke, and evaluate consent — no admin access',          TRUE, TRUE),
    ('org-admin',         NULL, 'tenant',   'Organization Admin',   'Manage org members and roles, plus full tenant access',          TRUE, TRUE),
    ('viewer',            NULL, 'tenant',   'Viewer',               'Read-only access to consent evaluation and catalogue browsing',  TRUE, TRUE);

-- ============================================================================
-- Seed: role → permission mappings
-- ============================================================================

-- super-admin: ALL permissions
INSERT INTO platform_iam.role_permission (role_id, permission_id)
SELECT 'super-admin', permission_id FROM platform_iam.permission;

-- tenant-admin: all tenant-scoped permissions (no platform:* or iam:user:*)
INSERT INTO platform_iam.role_permission (role_id, permission_id)
SELECT 'tenant-admin', permission_id FROM platform_iam.permission
WHERE domain IN ('consent', 'catalogue', 'governance', 'events')
   OR permission_id IN ('org:read', 'org:write', 'org:member:manage', 'org:role:manage');

-- org-admin: same as tenant-admin (org management is the key differentiator in assignment context)
INSERT INTO platform_iam.role_permission (role_id, permission_id)
SELECT 'org-admin', permission_id FROM platform_iam.permission
WHERE domain IN ('consent', 'catalogue', 'governance', 'events')
   OR permission_id IN ('org:read', 'org:write', 'org:member:manage', 'org:role:manage');

-- tenant-write: all consent (except dsar), catalogue, governance, events
INSERT INTO platform_iam.role_permission (role_id, permission_id) VALUES
    ('tenant-write', 'consent:grant'),
    ('tenant-write', 'consent:revoke'),
    ('tenant-write', 'consent:evaluate'),
    ('tenant-write', 'consent:read'),
    ('tenant-write', 'consent:export'),
    ('tenant-write', 'consent:graph:read'),
    ('tenant-write', 'catalogue:read'),
    ('tenant-write', 'catalogue:write'),
    ('tenant-write', 'governance:read'),
    ('tenant-write', 'governance:write'),
    ('tenant-write', 'events:read'),
    ('tenant-write', 'org:read');

-- tenant-read: read-only across all tenant domains
INSERT INTO platform_iam.role_permission (role_id, permission_id) VALUES
    ('tenant-read', 'consent:evaluate'),
    ('tenant-read', 'consent:read'),
    ('tenant-read', 'consent:graph:read'),
    ('tenant-read', 'catalogue:read'),
    ('tenant-read', 'governance:read'),
    ('tenant-read', 'events:read'),
    ('tenant-read', 'org:read');

-- consent-operator: focused consent operations only
INSERT INTO platform_iam.role_permission (role_id, permission_id) VALUES
    ('consent-operator', 'consent:grant'),
    ('consent-operator', 'consent:revoke'),
    ('consent-operator', 'consent:evaluate'),
    ('consent-operator', 'consent:read'),
    ('consent-operator', 'consent:graph:read');

-- viewer: minimal read-only
INSERT INTO platform_iam.role_permission (role_id, permission_id) VALUES
    ('viewer', 'consent:evaluate'),
    ('viewer', 'consent:read'),
    ('viewer', 'catalogue:read'),
    ('viewer', 'governance:read');
