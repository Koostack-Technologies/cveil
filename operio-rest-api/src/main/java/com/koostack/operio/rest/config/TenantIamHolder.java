package com.koostack.operio.rest.config;

import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.tenant.iam.TenantIamCommandService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;

/**
 * Concrete holder for tenant-scoped IAM services.
 *
 * <p>Mirrors {@link PlatformCqrsHolder} — wraps nullable references so Quarkus ArC can inject them
 * without generic-type resolution issues. Services may be {@code null} in embedded mode or when
 * Keycloak is not configured.
 */
public class TenantIamHolder {

    private final TenantIamCommandService<?> commandService;
    private final TenantIamQueryService<?> queryService;

    @SuppressWarnings("rawtypes")
    public TenantIamHolder(
            TenantIamCommandService commandService, TenantIamQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    public boolean isAvailable() {
        return commandService != null && queryService != null;
    }

    @SuppressWarnings("unchecked")
    public TenantIamCommandService<?> commandService() {
        return commandService;
    }

    @SuppressWarnings("unchecked")
    public TenantIamQueryService<?> queryService() {
        return queryService;
    }

    /**
     * Returns the tenant IAM query dispatcher for direct query-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a QueryDispatcher
     */
    public QueryDispatcher queryDispatcher() {
        if (queryService instanceof QueryDispatcher qd) {
            return qd;
        }
        throw new IllegalStateException(
                "Tenant IAM query service does not implement QueryDispatcher");
    }
}
