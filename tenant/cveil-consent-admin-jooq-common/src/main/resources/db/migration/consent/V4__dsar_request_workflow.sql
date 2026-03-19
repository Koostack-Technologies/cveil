-- V4: DSAR (Data Subject Access Request) request workflow tables.
-- Tracks incoming data subject requests and their fulfillment lifecycle.

CREATE TABLE dsar_request (
    dsar_request_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dsar_request_uuid  UUID          NOT NULL UNIQUE,
    tenant_id          VARCHAR(255)  NOT NULL,
    subject_id         VARCHAR(256),
    requester_email    VARCHAR(512)  NOT NULL,
    requester_name     VARCHAR(256),
    request_type       VARCHAR(32)   NOT NULL,
    status             VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    legal_basis        VARCHAR(128),
    notes              TEXT,
    admin_notes        TEXT,
    fulfilled_at       TIMESTAMP,
    due_date           TIMESTAMP,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dsar_request_tenant     ON dsar_request (tenant_id);
CREATE INDEX idx_dsar_request_status     ON dsar_request (tenant_id, status);
CREATE INDEX idx_dsar_request_subject    ON dsar_request (tenant_id, subject_id);
CREATE INDEX idx_dsar_request_email      ON dsar_request (tenant_id, requester_email);
CREATE INDEX idx_dsar_request_due        ON dsar_request (tenant_id, due_date);

CREATE TABLE dsar_activity_log (
    dsar_activity_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dsar_activity_uuid UUID          NOT NULL UNIQUE,
    tenant_id          VARCHAR(255)  NOT NULL,
    request_uuid       UUID          NOT NULL,
    action             VARCHAR(64)   NOT NULL,
    performed_by       VARCHAR(256),
    details            TEXT,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dsar_activity_tenant  ON dsar_activity_log (tenant_id);
CREATE INDEX idx_dsar_activity_request ON dsar_activity_log (request_uuid);
