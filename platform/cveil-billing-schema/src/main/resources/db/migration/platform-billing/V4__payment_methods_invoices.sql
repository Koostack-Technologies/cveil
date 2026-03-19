-- ============================================================================
-- V4: Payment methods, invoices, and Stripe event log
--
-- Extends the billing schema with:
--   1. payment_method   — non-sensitive mirror of external payment methods
--   2. invoice          — full mirror of external invoices
--   3. invoice_line_item — line-item breakdown for each invoice
--   4. stripe_event_log — idempotency + audit trail for webhook events
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Payment methods (reference mirror — no sensitive card data stored)
-- ---------------------------------------------------------------------------
CREATE TABLE payment_method (
    id                          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   VARCHAR(255)    NOT NULL,
    stripe_payment_method_id    VARCHAR(255)    NOT NULL UNIQUE,
    type                        VARCHAR(50)     NOT NULL,
    card_brand                  VARCHAR(50),
    card_last4                  VARCHAR(4),
    card_exp_month              SMALLINT,
    card_exp_year               SMALLINT,
    is_default                  BOOLEAN         NOT NULL DEFAULT FALSE,
    status                      VARCHAR(50)     NOT NULL DEFAULT 'active'
                                    CHECK (status IN ('active', 'detached', 'expired')),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_method_tenant
    ON payment_method(tenant_id);

-- ---------------------------------------------------------------------------
-- 2. Invoices (full mirror)
-- ---------------------------------------------------------------------------
CREATE TABLE invoice (
    id                          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   VARCHAR(255)    NOT NULL,
    stripe_invoice_id           VARCHAR(255)    NOT NULL UNIQUE,
    stripe_subscription_id      VARCHAR(255),
    status                      VARCHAR(50)     NOT NULL
                                    CHECK (status IN ('draft', 'open', 'paid', 'void', 'uncollectible')),
    currency                    VARCHAR(10)     NOT NULL DEFAULT 'usd',
    amount_due                  BIGINT          NOT NULL DEFAULT 0,
    amount_paid                 BIGINT          NOT NULL DEFAULT 0,
    amount_remaining            BIGINT          NOT NULL DEFAULT 0,
    subtotal                    BIGINT          NOT NULL DEFAULT 0,
    tax                         BIGINT          DEFAULT 0,
    total                       BIGINT          NOT NULL DEFAULT 0,
    hosted_invoice_url          TEXT,
    invoice_pdf_url             TEXT,
    period_start                TIMESTAMPTZ,
    period_end                  TIMESTAMPTZ,
    due_date                    TIMESTAMPTZ,
    paid_at                     TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoice_tenant
    ON invoice(tenant_id);

CREATE INDEX idx_invoice_tenant_status
    ON invoice(tenant_id, status);

-- ---------------------------------------------------------------------------
-- 3. Invoice line items
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_line_item (
    id                          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id                  UUID            NOT NULL
                                    REFERENCES invoice(id) ON DELETE CASCADE,
    stripe_line_item_id         VARCHAR(255)    NOT NULL,
    description                 TEXT,
    amount                      BIGINT          NOT NULL DEFAULT 0,
    currency                    VARCHAR(10)     NOT NULL DEFAULT 'usd',
    quantity                    INTEGER,
    unit_amount                 BIGINT,
    period_start                TIMESTAMPTZ,
    period_end                  TIMESTAMPTZ,
    proration                   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoice_line_item_invoice
    ON invoice_line_item(invoice_id);

-- ---------------------------------------------------------------------------
-- 4. Stripe webhook event log (idempotency + audit)
-- ---------------------------------------------------------------------------
CREATE TABLE stripe_event_log (
    stripe_event_id             VARCHAR(255)    PRIMARY KEY,
    event_type                  VARCHAR(255)    NOT NULL,
    processed_at                TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload                     JSONB
);
