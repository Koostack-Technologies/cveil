CREATE SCHEMA IF NOT EXISTS platform_billing;

CREATE TABLE platform_billing.billing_profile (
                                                  tenant_id               VARCHAR(255)  PRIMARY KEY REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
                                                  payment_processor       VARCHAR(50)   NOT NULL DEFAULT 'stripe',
                                                  external_customer_id    VARCHAR(255)  UNIQUE,
                                                  billing_email           VARCHAR(255)  NOT NULL,
                                                  tax_id                  VARCHAR(50),
                                                  payment_method_status   VARCHAR(50)   NOT NULL DEFAULT 'unconfigured'
                                                      CHECK (payment_method_status IN ('valid', 'invalid', 'unconfigured')),
                                                  created_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  updated_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_billing.subscription (
                                               subscription_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                               tenant_id                 VARCHAR(255)  NOT NULL REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
                                               external_subscription_id  VARCHAR(255)  UNIQUE,
                                               plan_tier                 VARCHAR(50)   NOT NULL,

                                               eval_limit_monthly        BIGINT        NOT NULL DEFAULT 10000,
                                               subject_limit             BIGINT        NOT NULL DEFAULT 1000,
                                               burst_limit_per_min       INT           NOT NULL DEFAULT 100,

                                               status                    VARCHAR(50)   NOT NULL
                                                   CHECK (status IN ('active', 'canceled', 'incomplete', 'past_due', 'trialing')),

                                               current_period_start      TIMESTAMPTZ   NOT NULL,
                                               current_period_end        TIMESTAMPTZ   NOT NULL,
                                               cancel_at_period_end      BOOLEAN       NOT NULL DEFAULT FALSE,

                                               created_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_billing.daily_usage_rollup (
                                                     tenant_id           VARCHAR(255)  NOT NULL REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
                                                     usage_date          DATE          NOT NULL,
                                                     metric_name         VARCHAR(50)   NOT NULL,
                                                     total_count         BIGINT        NOT NULL DEFAULT 0,
                                                     synced_to_billing   BOOLEAN       NOT NULL DEFAULT FALSE,
                                                     PRIMARY KEY (tenant_id, usage_date, metric_name)
);

CREATE INDEX idx_daily_usage_sync ON platform_billing.daily_usage_rollup(synced_to_billing) WHERE synced_to_billing = FALSE;