package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * jOOQ-backed {@link SubjectDsarService} implementation.
 *
 * <p>Creates a {@link SubjectDsarRepository} during {@link #configure}
 * and delegates all operations to it.
 */
public class JooqSubjectDsarService
    implements SubjectDsarService<JooqConsentAdminServiceConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqSubjectDsarService.class
    );

    private DSLContext dsl;
    private SubjectDsarRepository subjectDsarRepository;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-subject-dsar";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqConsentAdminServiceConfig> getConfigurationType() {
        return JooqConsentAdminServiceConfig.class;
    }

    @Override
    public boolean isReady() {
        if (dsl == null) return false;
        try {
            dsl.selectOne().fetch();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void configure(JooqConsentAdminServiceConfig config) {
        this.dsl = JooqContextFactory.createTenantAwareContext(
            config,
            "operio-subject-dsar-jooq",
            "classpath:db/migration/consent"
        );
        this.subjectDsarRepository = new SubjectDsarRepository(dsl);
        LOG.info("JooqSubjectDsarService configured");
    }

    // -----------------------------------------------------------------------
    // SubjectDsarService operations
    // -----------------------------------------------------------------------

    private DSLContext getDsl() {
        if (dsl == null) {
            throw new OperioConfigurationException(
                "JooqSubjectDsarService not configured. Call configure() first."
            );
        }
        return dsl;
    }

    @Override
    public Optional<String> findKeyByDsarId(UUID dsarId) {
        getDsl();
        return subjectDsarRepository.findKeyByDsarId(dsarId);
    }

    @Override
    public void saveKey(UUID dsarId, String encryptedKey) {
        getDsl();
        subjectDsarRepository.saveKey(dsarId, encryptedKey);
    }

    @Override
    public void deleteKey(UUID dsarId) {
        getDsl();
        subjectDsarRepository.deleteKey(dsarId);
    }

    @Override
    public boolean allEventsPublished(UUID dsarId) {
        getDsl();
        return subjectDsarRepository.allEventsPublished(dsarId);
    }
}
