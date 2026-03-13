package com.koostack.cveil.core.tenant.consent;

/**
 * Periodic service that sweeps for expired consent grants and issues {@code CONSENT_EXPIRED}
 * commands for each expired entry.
 *
 * <p>Implementation reads from {@link ConsentAdminQueryService} to find consent ledger entries past
 * their {@code expiresAt}, then calls {@link ConsentAdminCommandService#expireConsentCommand} for
 * each. This causes a {@code CONSENT_EXPIRED} event to flow to the admin and decision projections.
 */
public interface ExpirationSweeperService {

    /**
     * Sweep for expired grants in the current tenant and expire them.
     *
     * <p>Requires {@code TenantContext} to be bound for the current scope.
     *
     * @return the number of grants expired
     */
    int sweep();

    /**
     * Sweep for expired grants across all tenants.
     *
     * <p>Iterates all known tenants and invokes {@link #sweep()} within each tenant context.
     *
     * @return the total number of grants expired across all tenants
     */
    int sweepAll();
}
