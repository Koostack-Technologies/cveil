package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import org.jooq.DSLContext;

public class JooqConsentAdminService
    implements ConsentAdminService<JooqConsentAdminServiceConfig>
{

    private DSLContext dslContext;

    private DSLContext getDsl() {
        if (dslContext == null) {
            throw new IllegalStateException(
                "JooqConsentAdminService not configured. Call configure() with a valid JooqConsentAdminServiceConfig first."
            );
        }
        
        return dslContext;
    }

    @Override
    public String getName() {
        return "jooq";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public void grantConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, "GRANTED");
    }

    @Override
    public void revokeConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, "REVOKED");
    }

    private void upsert(String subject, ConsentScope scope, String status) {}

    @Override
    public Class<JooqConsentAdminServiceConfig> getConfigurationType() {
        return JooqConsentAdminServiceConfig.class;
    }

    @Override
    public void configure(JooqConsentAdminServiceConfig config) {
        this.dslContext = JooqContextFactory.createContext(
            config,
            "operio-consent-admin-jooq",
            "classpath:db/migration/consent"
        );
    }
}
