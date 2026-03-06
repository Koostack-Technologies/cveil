package com.koostack.operio.platform.tenant.jooq;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.impl.DSL;

/**
 * JOOQ-backed implementation of {@link TenantRepository} that queries
 * the shared {@code platform_core.tenant} table.
 */
public class JooqTenantRepository implements TenantRepository {

    private final DSLContext dsl;

    public JooqTenantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TenantRecord> findAll() {
        return dsl.select(
                DSL.field("tenant_id", String.class),
                DSL.field("status", String.class),
                DSL.field("schema_prefix", String.class),
                DSL.field("redis_acl_password", String.class)
            )
            .from(DSL.table("platform_core.tenant"))
            .fetch(r -> new TenantRecord(
                r.get("tenant_id", String.class),
                r.get("status", String.class),
                r.get("schema_prefix", String.class),
                r.get("redis_acl_password", String.class)
            ));
    }

    @Override
    public int updateRedisAclCredentials(
        String tenantId,
        String redisAclUser,
        String redisAclPassword,
        String redisAclPassHash,
        String actor
    ) {
        return dsl.update(DSL.table("platform_core.tenant"))
            .set(DSL.field("redis_acl_user", String.class), redisAclUser)
            .set(DSL.field("redis_acl_password", String.class), redisAclPassword)
            .set(DSL.field("redis_acl_pass_hash", String.class), redisAclPassHash)
            .set(DSL.field("updated_at", java.time.OffsetDateTime.class),
                java.time.OffsetDateTime.now())
            .where(DSL.field("tenant_id", String.class).eq(tenantId))
            .execute();
    }
}
