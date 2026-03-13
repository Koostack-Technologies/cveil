package com.koostack.cveil.core.tenant.iam;

/**
 * Tenant-scoped role constants.
 *
 * <p>These roles are created as realm-level roles in each tenant's Keycloak realm during
 * provisioning. They are separate from the existing consent/admin roles used for data operations.
 */
public final class TenantRoles {

    private TenantRoles() {}

    /** Full IAM management — CRUD users, service accounts, invite. */
    public static final String TENANT_ADMIN = "tenant-admin";

    /** CRUD on catalogue, subject, governance, consent. Self-manage only (no IAM for others). */
    public static final String TENANT_WRITE = "tenant-write";

    /** Read-only access to catalogue, subject, governance, consent. */
    public static final String TENANT_READ = "tenant-read";
}
