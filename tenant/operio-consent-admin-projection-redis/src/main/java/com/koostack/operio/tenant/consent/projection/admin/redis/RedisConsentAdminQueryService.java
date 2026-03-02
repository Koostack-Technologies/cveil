package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Read-only query service backed by the Redis admin projection.
 *
 * <p>Returns {@code null} or empty lists on cache miss. Not intended to
 * be used directly — use {@link RedisFirstConsentAdminQueryService} which
 * wraps this with a JOOQ fallback.
 */
public class RedisConsentAdminQueryService implements ConsentAdminQueryService<Void> {

    private static final Logger log = LoggerFactory.getLogger(RedisConsentAdminQueryService.class);

    private final ConsentAdminRedisRepository repository;

    public RedisConsentAdminQueryService(ConsentAdminRedisRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    // ── ConsentAdminQueryService ────────────────────────────────────────────

    @Override
    public List<FieldView> listFields() {
        try {
            List<FieldView> result = repository.listFields();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("Redis listFields() failed", e);
            return null;
        }
    }

    @Override
    public FieldView getField(String fieldName, String entityName) {
        try {
            return repository.getField(fieldName, entityName);
        } catch (Exception e) {
            log.warn("Redis getField({}/{}) failed", entityName, fieldName, e);
            return null;
        }
    }

    @Override
    public List<PurposeView> listPurposes() {
        try {
            List<PurposeView> result = repository.listPurposes();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("Redis listPurposes() failed", e);
            return null;
        }
    }

    @Override
    public PurposeView getPurpose(String purposeName) {
        try {
            return repository.getPurpose(purposeName);
        } catch (Exception e) {
            log.warn("Redis getPurpose({}) failed", purposeName, e);
            return null;
        }
    }

    @Override
    public List<RegulationView> listRegulations() {
        try {
            List<RegulationView> result = repository.listRegulations();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("Redis listRegulations() failed", e);
            return null;
        }
    }

    @Override
    public RegulationView getRegulation(String regulationName) {
        try {
            return repository.getRegulation(regulationName);
        } catch (Exception e) {
            log.warn("Redis getRegulation({}) failed", regulationName, e);
            return null;
        }
    }

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        try {
            return repository.getSubjectConsent(subjectId);
        } catch (Exception e) {
            log.warn("Redis getSubjectConsent({}) failed", subjectId, e);
            return null;
        }
    }

    @Override
    public int countSubjectConsentEntries(String subjectId) {
        SubjectConsentView view = getSubjectConsent(subjectId);
        return view != null ? view.entries().size() : -1;
    }

    @Override
    public PurposeView.PolicyView findPolicy(String purposeName, String policyType,
                                              String fieldName, String entityName,
                                              String categoryName) {
        // Not efficiently supported by Redis — delegate to fallback
        return null;
    }

    // ── Plugin SPI ─────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "redis-consent-admin-query";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public Class<Void> getConfigurationType() {
        return Void.class;
    }

    @Override
    public void configure(Void config) {
        // No configuration needed — dependencies injected via constructor
    }

    @Override
    public boolean isReady() {
        return repository != null;
    }
}
