package com.koostack.operio.platform.tenant.jooq;

import java.util.List;

/**
 * Repository abstraction for tenant lifecycle queries needed by {@link JooqTenantManagerService}.
 *
 * <p>Implementations should target the shared platform schema's {@code tenant} table.
 */
public interface TenantRepository {

    List<TenantRecord> findAll();

    int updateRedisAclCredentials(
            String tenantId,
            String redisAclUser,
            String redisAclPassword,
            String redisAclPassHash,
            String actor);
}
