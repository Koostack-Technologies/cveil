package com.koostack.operio.core.platform.core;

import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.spi.Plugin;

import java.util.List;

/**
 * Query service for the core platform domain (tenant read model).
 *
 * <p>Reads from the materialized {@code platform_core.tenant} table
 * populated by the {@link CoreProjection}.
 */
public interface CoreQueryService<T> extends Plugin<T> {

    /** Retrieve a single tenant by its business identifier, or {@code null} if not found. */
    TenantInfo getTenant(String tenantId);

    /** List all tenants. */
    List<TenantInfo> listTenants();

    /** List tenants filtered by status (e.g. "active", "suspended", "created"). */
    List<TenantInfo> listTenantsByStatus(String status);
}
