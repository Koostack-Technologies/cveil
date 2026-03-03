package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.platform.iam.IamQueryService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

import static org.jooq.impl.DSL.*;

public class JooqIamQueryService implements IamQueryService<JooqIamQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamQueryService.class);

    private static final org.jooq.Table<?> API_KEY = table(name("platform_iam", "api_key"));
    private static final org.jooq.Field<String> KEY_ID = field(name("key_id"), String.class);
    private static final org.jooq.Field<String> KEY_HASH = field(name("key_hash"), String.class);
    private static final org.jooq.Field<String> KEY_PREFIX = field(name("key_prefix"), String.class);
    private static final org.jooq.Field<String> NAME = field(name("name"), String.class);
    private static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> ENVIRONMENT = field(name("environment"), String.class);
    private static final org.jooq.Field<String> SUBJECT = field(name("subject"), String.class);
    private static final org.jooq.Field<String> ROLES = field(name("roles"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT = field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> EXPIRES_AT = field(name("expires_at"), OffsetDateTime.class);

    private DSLContext dsl;

    @Override public String getName() { return "jooq-iam-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqIamQueryConfig> getConfigurationType() { return JooqIamQueryConfig.class; }

    @Override
    public void configure(JooqIamQueryConfig config) {
        this.dsl = config.dsl();
        LOG.info("JooqIamQueryService configured");
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    @Override
    public List<ApiKeyInfo> listApiKeys(String tenantId) {
        return dsl.select()
            .from(API_KEY)
            .where(TENANT_ID.eq(tenantId))
            .orderBy(CREATED_AT.desc())
            .fetch(this::toApiKeyInfo);
    }

    @Override
    public ApiKeyInfo getApiKey(String tenantId, String keyId) {
        Record record = dsl.select()
            .from(API_KEY)
            .where(TENANT_ID.eq(tenantId))
            .and(KEY_ID.eq(keyId))
            .fetchOne();
        return record != null ? toApiKeyInfo(record) : null;
    }

    @Override
    public ApiKeyInfo findByKeyHash(String keyHash) {
        Record record = dsl.select()
            .from(API_KEY)
            .where(KEY_HASH.eq(keyHash))
            .fetchOne();
        return record != null ? toApiKeyInfo(record) : null;
    }

    @Override
    public ApiKeyInfo findByKeyPrefix(String keyPrefix) {
        Record record = dsl.select()
            .from(API_KEY)
            .where(KEY_PREFIX.eq(keyPrefix))
            .fetchOne();
        return record != null ? toApiKeyInfo(record) : null;
    }

    private ApiKeyInfo toApiKeyInfo(Record record) {
        OffsetDateTime created = record.get(CREATED_AT);
        OffsetDateTime expires = record.get(EXPIRES_AT);
        return new ApiKeyInfo(
            record.get(KEY_ID),
            record.get(KEY_PREFIX),
            record.get(NAME),
            record.get(TENANT_ID),
            record.get(ENVIRONMENT),
            record.get(SUBJECT),
            record.get(ROLES),
            record.get(STATUS),
            created != null ? created.toInstant() : null,
            expires != null ? expires.toInstant() : null
        );
    }
}
