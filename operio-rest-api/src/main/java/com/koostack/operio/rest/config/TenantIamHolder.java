package com.koostack.operio.rest.config;

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
}
