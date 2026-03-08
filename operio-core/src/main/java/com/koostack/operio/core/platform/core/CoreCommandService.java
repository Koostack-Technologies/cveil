package com.koostack.operio.core.platform.core;

import com.koostack.operio.core.spi.Plugin;

/**
 * Command service for the core platform domain (tenant lifecycle).
 *
 * <p>All commands publish semantic domain events to the platform event store via the configured
 * {@link com.koostack.operio.core.event.EventService}. No direct database writes occur here — the
 * read model is materialized by the corresponding {@link CoreProjection}.
 */
public interface CoreCommandService<T> extends Plugin<T> {

    /** Create a new tenant with the given business identifier, display name, and plan. */
    void createTenant(String tenantId, String displayName, String plan);

    /** Update the display name of an existing tenant. */
    void updateTenantDisplayName(String tenantId, String displayName);

    /** Change the plan tier of an existing tenant. The old plan is resolved internally. */
    void changeTenantPlan(String tenantId, String newPlan);

    /** Suspend a tenant, optionally providing a reason. */
    void suspendTenant(String tenantId, String reason);

    /** Re-activate a previously suspended tenant. */
    void activateTenant(String tenantId);

    /** Soft-delete a tenant. */
    void deleteTenant(String tenantId);

    /**
     * Mark a tenant as fully provisioned after infrastructure setup completes.
     *
     * <p>Typically called by the tenant-manager projection handler once Postgres schemas, Redis
     * ACLs, and Flyway migrations have succeeded.
     */
    void provisionTenant(String tenantId, String schemaPrefix, String redisAclUser);

    /**
     * Emit a {@code TenantProvisioningStarted} event to signal that async infrastructure
     * provisioning should begin. Called after tenant, billing, and subscription records have been
     * created.
     */
    void emitProvisioningStarted(String tenantId, String displayName, String plan);
}
