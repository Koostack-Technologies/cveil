-- V3: Banner configuration for embeddable consent widget.
-- Each tenant can configure one or more site IDs that map to a banner
-- embed. The public consent API resolves the tenant from the site_id.

CREATE TABLE banner_config (
    banner_config_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    banner_config_uuid UUID          NOT NULL UNIQUE,
    tenant_id          VARCHAR(255)  NOT NULL,
    site_id            VARCHAR(128)  NOT NULL UNIQUE,
    site_domain        VARCHAR(512)  NOT NULL,
    banner_position    VARCHAR(32)   NOT NULL DEFAULT 'bottom',
    banner_theme       TEXT          DEFAULT '{}',
    custom_text        TEXT          DEFAULT '{}',
    purposes           TEXT          NOT NULL,
    cookie_categories  TEXT          DEFAULT '[]',
    enabled            BOOLEAN       NOT NULL DEFAULT true,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_banner_config_tenant  ON banner_config (tenant_id);
CREATE INDEX idx_banner_config_site_id ON banner_config (site_id);
