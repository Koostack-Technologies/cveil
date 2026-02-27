package com.koostack.operio.core.data.consent;

/**
 * Periodic service that sweeps for expired consent policies and issues
 * {@code POLICY_REVOKED} commands for each expired policy.
 *
 * <p>Implementation reads from {@link ConsentAdminQueryService} to find
 * policies past their {@code expiresAt}, then calls
 * {@link ConsentAdminCommandService#revokeConsent} for each expired policy.
 * This causes a {@code POLICY_REVOKED} event to flow to the decision
 * projection, which deletes the Redis entry.
 */
public interface ExpirationSweeperService {

    /**
     * Sweep for expired policies in the current tenant and revoke them.
     *
     * <p>Requires {@code TenantContext} to be bound for the current scope.
     *
     * @return the number of policies revoked
     */
    int sweep();

    /**
     * Sweep for expired policies across all tenants.
     *
     * <p>Iterates all known tenants and invokes {@link #sweep()} within
     * each tenant context.
     *
     * @return the total number of policies revoked across all tenants
     */
    int sweepAll();
}
