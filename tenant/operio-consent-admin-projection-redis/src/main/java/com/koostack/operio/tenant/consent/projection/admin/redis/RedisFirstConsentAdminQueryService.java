package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Composite query service that tries the Redis-backed service first,
 * falling back to the JOOQ-backed service on cache miss.
 *
 * <p>A "miss" is defined as {@code null} for single-entity queries or
 * {@code null} for list queries (empty lists from Redis also trigger
 * fallback since empty may mean "not populated" rather than "no data").
 *
 * <p>This service is wired explicitly in OperioProducers (not via SPI
 * discovery) because it wraps two already-configured query services.
 */
public class RedisFirstConsentAdminQueryService implements ConsentAdminQueryService<Void> {

    private static final Logger log = LoggerFactory.getLogger(RedisFirstConsentAdminQueryService.class);

    private final RedisConsentAdminQueryService redisService;
    private final ConsentAdminQueryService<?> fallbackService;

    public RedisFirstConsentAdminQueryService(
            RedisConsentAdminQueryService redisService,
            ConsentAdminQueryService<?> fallbackService) {
        this.redisService = Objects.requireNonNull(redisService, "redisService");
        this.fallbackService = Objects.requireNonNull(fallbackService, "fallbackService");
    }

    // ── Field Queries ──────────────────────────────────────────────────────

    @Override
    public List<FieldView> listFields() {
        List<FieldView> result = redisService.listFields();
        if (result != null) return result;
        log.debug("Redis miss for listFields() — falling back to JOOQ");
        return fallbackService.listFields();
    }

    @Override
    public FieldView getField(String fieldName, String entityName) {
        FieldView result = redisService.getField(fieldName, entityName);
        if (result != null) return result;
        log.debug("Redis miss for getField({}/{}) — falling back to JOOQ", entityName, fieldName);
        return fallbackService.getField(fieldName, entityName);
    }

    // ── Purpose Queries ────────────────────────────────────────────────────

    @Override
    public List<PurposeView> listPurposes() {
        List<PurposeView> result = redisService.listPurposes();
        if (result != null) return result;
        log.debug("Redis miss for listPurposes() — falling back to JOOQ");
        return fallbackService.listPurposes();
    }

    @Override
    public PurposeView getPurpose(String purposeName) {
        PurposeView result = redisService.getPurpose(purposeName);
        if (result != null) return result;
        log.debug("Redis miss for getPurpose({}) — falling back to JOOQ", purposeName);
        return fallbackService.getPurpose(purposeName);
    }

    // ── Regulation Queries ─────────────────────────────────────────────────

    @Override
    public List<RegulationView> listRegulations() {
        List<RegulationView> result = redisService.listRegulations();
        if (result != null) return result;
        log.debug("Redis miss for listRegulations() — falling back to JOOQ");
        return fallbackService.listRegulations();
    }

    @Override
    public RegulationView getRegulation(String regulationName) {
        RegulationView result = redisService.getRegulation(regulationName);
        if (result != null) return result;
        log.debug("Redis miss for getRegulation({}) — falling back to JOOQ", regulationName);
        return fallbackService.getRegulation(regulationName);
    }

    // ── SubjectConsent Queries ─────────────────────────────────────────────

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        SubjectConsentView result = redisService.getSubjectConsent(subjectId);
        if (result != null) return result;
        log.debug("Redis miss for getSubjectConsent({}) — falling back to JOOQ", subjectId);
        return fallbackService.getSubjectConsent(subjectId);
    }

    @Override
    public int countSubjectConsentEntries(String subjectId) {
        int count = redisService.countSubjectConsentEntries(subjectId);
        if (count >= 0) return count;
        return fallbackService.countSubjectConsentEntries(subjectId);
    }

    // ── Expiration & Policy Queries (always JOOQ) ──────────────────────────

    @Override
    public List<SubjectConsentView.ConsentLedgerEntryView> findExpiredGrants(Instant cutoff) {
        // Expiration scan requires full table traversal — always use JOOQ
        return fallbackService.findExpiredGrants(cutoff);
    }

    @Override
    public PurposeView.PolicyView findPolicy(String purposeName, String policyType,
                                              String fieldName, String entityName,
                                              String categoryName) {
        // Complex lookup — always use JOOQ
        return fallbackService.findPolicy(purposeName, policyType, fieldName, entityName, categoryName);
    }

    // ── Plugin SPI ─────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "redis-first-consent-admin-query";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public Class<Void> getConfigurationType() {
        return Void.class;
    }

    @Override
    public void configure(Void config) {
        // No-op — dependencies injected via constructor
    }

    @Override
    public boolean isReady() {
        return fallbackService.isReady();
    }
}
