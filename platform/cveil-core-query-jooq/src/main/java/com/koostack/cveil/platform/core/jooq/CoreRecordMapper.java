package com.koostack.cveil.platform.core.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.platform.core.models.TenantCredentials;
import com.koostack.cveil.core.platform.core.models.TenantInfo;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.function.UnaryOperator;
import org.jooq.Record;

/** Shared jOOQ field constants and record-to-domain mapping utilities for core tenant queries. */
public final class CoreRecordMapper {

    static final org.jooq.Table<?> TENANT = table(name("platform_core", "tenant"));
    static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> DISPLAY_NAME = field(name("display_name"), String.class);
    static final org.jooq.Field<String> PLAN = field(name("plan"), String.class);
    static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    static final org.jooq.Field<String> REDIS_ACL_USER =
            field(name("redis_acl_user"), String.class);
    static final org.jooq.Field<String> REDIS_ACL_PASSWORD =
            field(name("redis_acl_password"), String.class);
    static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    private final UnaryOperator<String> decryptor;

    CoreRecordMapper(UnaryOperator<String> decryptor) {
        this.decryptor = decryptor != null ? decryptor : UnaryOperator.identity();
    }

    TenantInfo toTenantInfo(Record r) {
        return new TenantInfo(
                r.get(TENANT_ID),
                r.get(DISPLAY_NAME),
                r.get(PLAN),
                r.get(STATUS),
                toInstant(r.get(CREATED_AT)),
                toInstant(r.get(UPDATED_AT)));
    }

    TenantCredentials toTenantCredentials(Record r) {
        String aclUser = r.get(REDIS_ACL_USER);
        String aclPassword = r.get(REDIS_ACL_PASSWORD);
        if (aclUser == null || aclUser.isBlank() || aclPassword == null || aclPassword.isBlank()) {
            return null;
        }
        return new TenantCredentials(aclUser, decryptor.apply(aclPassword));
    }

    static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
