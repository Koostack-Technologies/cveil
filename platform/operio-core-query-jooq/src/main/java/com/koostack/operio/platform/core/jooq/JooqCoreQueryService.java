package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.core.models.TenantInfo;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.jooq.impl.DSL.*;

public class JooqCoreQueryService implements CoreQueryService<JooqCoreQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreQueryService.class);

    private static final org.jooq.Table<?> TENANT = table(name("platform_core", "tenant"));
    private static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME = field(name("display_name"), String.class);
    private static final org.jooq.Field<String> PLAN = field(name("plan"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<String> SCHEMA_PREFIX = field(name("schema_prefix"), String.class);
    private static final org.jooq.Field<String> REDIS_ACL_USER = field(name("redis_acl_user"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT = field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> UPDATED_AT = field(name("updated_at"), OffsetDateTime.class);

    private DSLContext dsl;

    @Override public String getName() { return "jooq-core-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqCoreQueryConfig> getConfigurationType() { return JooqCoreQueryConfig.class; }

    @Override
    public void configure(JooqCoreQueryConfig config) {
        this.dsl = config.dsl();
        LOG.info("JooqCoreQueryService configured");
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    @Override
    public TenantInfo getTenant(String tenantId) {
        Record record = dsl.select()
            .from(TENANT)
            .where(TENANT_ID.eq(tenantId))
            .fetchOne();
        return record != null ? toTenantInfo(record) : null;
    }

    @Override
    public List<TenantInfo> listTenants() {
        return dsl.select()
            .from(TENANT)
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map(this::toTenantInfo);
    }

    @Override
    public List<TenantInfo> listTenantsByStatus(String status) {
        return dsl.select()
            .from(TENANT)
            .where(STATUS.eq(status))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map(this::toTenantInfo);
    }

    private TenantInfo toTenantInfo(Record r) {
        return new TenantInfo(
            r.get(TENANT_ID),
            r.get(DISPLAY_NAME),
            r.get(PLAN),
            r.get(STATUS),
            r.get(SCHEMA_PREFIX),
            r.get(REDIS_ACL_USER),
            toInstant(r.get(CREATED_AT)),
            toInstant(r.get(UPDATED_AT))
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
