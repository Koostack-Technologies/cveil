-- Plan table: stores subscription plan definitions for validation during tenant creation.
-- Plans are pre-seeded; no command/projection module yet.

CREATE TABLE platform_core.plan (
    plan_id         VARCHAR(50)   PRIMARY KEY,
    display_name    VARCHAR(200)  NOT NULL,
    description     TEXT,
    eval_limit      BIGINT        NOT NULL DEFAULT 0,
    subject_limit   BIGINT        NOT NULL DEFAULT 0,
    burst_limit     INTEGER       NOT NULL DEFAULT 0,
    is_public       BOOLEAN       NOT NULL DEFAULT TRUE,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed initial plans
INSERT INTO platform_core.plan (plan_id, display_name, description, eval_limit, subject_limit, burst_limit, is_public, sort_order) VALUES
    ('free',       'Free',       'Community tier with basic limits',     1000,    100,   10,  TRUE,  0),
    ('pro',        'Pro',        'Professional tier for growing teams', 50000,   5000,  100,  TRUE,  1),
    ('enterprise', 'Enterprise', 'Unlimited enterprise tier',          -1,      -1,    -1,   FALSE, 2);

-- Add FK from tenant.plan to plan.plan_id
ALTER TABLE platform_core.tenant
    ADD CONSTRAINT fk_tenant_plan FOREIGN KEY (plan) REFERENCES platform_core.plan(plan_id);
