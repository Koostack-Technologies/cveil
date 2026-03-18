-- ============================================================================
-- V11: Feature-gating table — controls which features are available per plan
--
-- Each row maps a (plan_id, feature_key) to an enabled flag and an optional
-- numeric limit.  The FeatureGateChecker bean reads this table (with caching)
-- to enforce plan-level access control on premium features.
-- ============================================================================

CREATE TABLE platform_core.plan_feature (
    plan_id         VARCHAR(50)   NOT NULL REFERENCES platform_core.plan(plan_id) ON DELETE CASCADE,
    feature_key     VARCHAR(100)  NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    limit_value     INTEGER,          -- NULL = unlimited when enabled; numeric = capped
    PRIMARY KEY (plan_id, feature_key)
);

-- ── Seed feature flags for each tier ────────────────────────────────────
-- Feature keys:
--   webhooks          — outbound webhook delivery
--   custom_banner     — embeddable consent banner customization
--   dsar              — DSAR request workflow
--   compliance_reports — compliance report generation & export
--   audit_export      — audit log CSV/JSON export
--   api_keys          — number of API keys per tenant
--   analytics         — analytics dashboard access

-- Free tier: basic access only
INSERT INTO platform_core.plan_feature (plan_id, feature_key, enabled, limit_value) VALUES
    ('free', 'webhooks',           FALSE, NULL),
    ('free', 'custom_banner',      TRUE,  1),      -- 1 site
    ('free', 'dsar',               FALSE, NULL),
    ('free', 'compliance_reports', FALSE, NULL),
    ('free', 'audit_export',       FALSE, NULL),
    ('free', 'api_keys',           TRUE,  2),
    ('free', 'analytics',          TRUE,  NULL);

-- Growth tier: core features unlocked
INSERT INTO platform_core.plan_feature (plan_id, feature_key, enabled, limit_value) VALUES
    ('pro', 'webhooks',           TRUE,  3),       -- 3 endpoints
    ('pro', 'custom_banner',      TRUE,  5),       -- 5 sites
    ('pro', 'dsar',               TRUE,  NULL),
    ('pro', 'compliance_reports', TRUE,  NULL),
    ('pro', 'audit_export',       TRUE,  NULL),
    ('pro', 'api_keys',           TRUE,  10),
    ('pro', 'analytics',          TRUE,  NULL);

-- Business tier: everything, higher limits
INSERT INTO platform_core.plan_feature (plan_id, feature_key, enabled, limit_value) VALUES
    ('business', 'webhooks',           TRUE,  20),
    ('business', 'custom_banner',      TRUE,  NULL),    -- unlimited sites
    ('business', 'dsar',               TRUE,  NULL),
    ('business', 'compliance_reports', TRUE,  NULL),
    ('business', 'audit_export',       TRUE,  NULL),
    ('business', 'api_keys',           TRUE,  50),
    ('business', 'analytics',          TRUE,  NULL);

-- Enterprise tier: unlimited everything
INSERT INTO platform_core.plan_feature (plan_id, feature_key, enabled, limit_value) VALUES
    ('enterprise', 'webhooks',           TRUE,  NULL),
    ('enterprise', 'custom_banner',      TRUE,  NULL),
    ('enterprise', 'dsar',               TRUE,  NULL),
    ('enterprise', 'compliance_reports', TRUE,  NULL),
    ('enterprise', 'audit_export',       TRUE,  NULL),
    ('enterprise', 'api_keys',           TRUE,  NULL),
    ('enterprise', 'analytics',          TRUE,  NULL);
