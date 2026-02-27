package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.ConsentAdminQueryService;
import com.koostack.operio.core.models.CategoryInfo;
import com.koostack.operio.core.models.ConsentAuditEntry;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.EntityInfo;
import com.koostack.operio.core.models.FieldInfo;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.PurposeInfo;
import com.koostack.operio.core.models.RegulationInfo;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentAuditLog;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-backed {@link ConsentAdminQueryService} implementation.
 *
 * <p>Handles all read operations for consent administration: listing
 * schema metadata, querying policies by subject, and retrieving
 * consent audit history.
 */
public class JooqConsentAdminQueryService
    implements ConsentAdminQueryService<JooqConsentAdminServiceConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminQueryService.class
    );

    private DSLContext dslContext;
    private EntityRepository entityRepository;
    private FieldRepository fieldRepository;
    private CategoryRepository categoryRepository;
    private ConsentPolicyRepository consentPolicyRepository;
    private PurposeRepository purposeRepository;
    private RegulationRepository regulationRepository;
    private ConsentAuditLogRepository consentAuditLogRepository;

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private DSLContext getDsl() {
        if (dslContext == null) {
            throw new OperioConfigurationException(
                "JooqConsentAdminQueryService not configured. Call configure() with a valid JooqConsentAdminServiceConfig first."
            );
        }
        return dslContext;
    }

    @Override
    public boolean isReady() {
        if (dslContext == null) {
            return false;
        }
        try {
            dslContext.selectOne().fetch();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-consent-admin-query";
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
    public void configure(JooqConsentAdminServiceConfig config) {
        this.dslContext = JooqContextFactory.createTenantAwareContext(
            config,
            "operio-consent-admin-query-jooq",
            "classpath:db/migration/consent"
        );
        this.entityRepository = new EntityRepository(dslContext);
        this.fieldRepository = new FieldRepository(dslContext);
        this.categoryRepository = new CategoryRepository(dslContext);
        this.consentPolicyRepository = new ConsentPolicyRepository(dslContext);
        this.purposeRepository = new PurposeRepository(dslContext);
        this.regulationRepository = new RegulationRepository(dslContext);
        this.consentAuditLogRepository = new ConsentAuditLogRepository(
            dslContext
        );
    }

    // -----------------------------------------------------------------------
    // Query methods
    // -----------------------------------------------------------------------

    @Override
    public List<FieldInfo> listFields() {
        getDsl(); // guard
        return fieldRepository
            .findAll()
            .stream()
            .map(f -> {
                String entityName = null;
                if (f.getEntityId() != null) {
                    entityName = entityRepository
                        .dao()
                        .findOptionalById(f.getEntityId())
                        .map(Entity::getEntityName)
                        .orElse(null);
                }
                return new FieldInfo(
                    f.getFieldName(),
                    entityName,
                    f.getDescription()
                );
            })
            .toList();
    }

    @Override
    public List<CategoryInfo> listCategories() {
        getDsl(); // guard
        return categoryRepository
            .findAll()
            .stream()
            .map(c -> new CategoryInfo(c.getCategoryName(), c.getDescription()))
            .toList();
    }

    @Override
    public List<EntityInfo> listEntities() {
        getDsl(); // guard
        return entityRepository
            .findAll()
            .stream()
            .map(e -> new EntityInfo(e.getEntityName(), e.getDescription()))
            .toList();
    }

    @Override
    public List<PurposeInfo> listPurposes() {
        getDsl(); // guard
        return purposeRepository
            .findAll()
            .stream()
            .map(p -> new PurposeInfo(p.getPurposeName(), p.getDescription()))
            .toList();
    }

    @Override
    public List<RegulationInfo> listRegulations() {
        getDsl(); // guard
        return regulationRepository
            .findAll()
            .stream()
            .map(r ->
                new RegulationInfo(
                    r.getRegulationName(),
                    r.getFullName(),
                    r.getJurisdiction(),
                    r.getDescription(),
                    r.getUrl()
                )
            )
            .toList();
    }

    @Override
    public List<PolicySummaryEntry> findPoliciesBySubject(String subject) {
        getDsl(); // guard
        List<ConsentPolicy> policies = consentPolicyRepository.findBySubject(
            subject
        );
        return policies.stream().map(this::toPolicySummary).toList();
    }

    @Override
    public int countPoliciesBySubject(String subjectId) {
        getDsl(); // guard
        return consentPolicyRepository.countBySubject(subjectId);
    }

    // -----------------------------------------------------------------------
    // Expiration queries
    // -----------------------------------------------------------------------

    @Override
    public List<PolicySummaryEntry> findExpiredPolicies(Instant cutoff) {
        getDsl(); // guard
        var cutoffLocal = java.time.LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        List<ConsentPolicy> expired = consentPolicyRepository.findExpired(cutoffLocal);
        return expired.stream().map(this::toPolicySummary).toList();
    }

    // -----------------------------------------------------------------------
    // Consent audit history
    // -----------------------------------------------------------------------

    @Override
    public List<ConsentAuditEntry> getConsentHistory(String subject) {
        getDsl(); // guard
        List<ConsentAuditLog> logs = consentAuditLogRepository.findBySubject(
            subject
        );
        return logs.stream().map(this::toAuditEntry).toList();
    }

    @Override
    public List<ConsentAuditEntry> getConsentHistory(
        String subject,
        ConsentScope scope
    ) {
        getDsl(); // guard

        Long fieldId = null;
        Long categoryId = null;
        Long purposeId = null;

        if (scope.fieldName() != null) {
            fieldId = resolveFieldId(scope.fieldName(), scope.entityName());
        }
        if (scope.category() != null) {
            categoryId = resolveCategoryId(scope.category());
        }
        if (scope.purposeName() != null && !scope.purposeName().isBlank()) {
            purposeId = resolvePurposeId(scope.purposeName());
        }

        List<ConsentAuditLog> logs =
            consentAuditLogRepository.findBySubjectAndScope(
                subject,
                fieldId,
                categoryId,
                purposeId
            );
        return logs.stream().map(this::toAuditEntry).toList();
    }

    // -----------------------------------------------------------------------
    // Internal — name-to-ID resolution
    // -----------------------------------------------------------------------

    private Long resolvePurposeId(String purposeName) {
        if (purposeName == null || purposeName.isBlank()) {
            throw new IllegalArgumentException(
                "purposeName must not be null or blank"
            );
        }
        Purpose purpose = purposeRepository
            .findByName(purposeName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No purpose found with name: " + purposeName
                )
            );
        return purpose.getPurposeId();
    }

    private Long resolveFieldId(String fieldName, String entityName) {
        if (entityName != null && !entityName.isBlank()) {
            Optional<Entity> entity = entityRepository.findByName(entityName);
            if (entity.isEmpty()) {
                throw new IllegalArgumentException(
                    "No entity found with name: " + entityName
                );
            }
            Optional<Field> field = fieldRepository.findByNameAndEntity(
                fieldName,
                entity.get().getEntityId()
            );
            if (field.isEmpty()) {
                throw new IllegalArgumentException(
                    "No field '" +
                        fieldName +
                        "' found under entity '" +
                        entityName +
                        "'"
                );
            }
            return field.get().getFieldId();
        }

        List<Field> fields = fieldRepository.findByName(fieldName);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(
                "No field found with name: " + fieldName
            );
        }
        if (fields.size() > 1) {
            throw new IllegalStateException(
                "Ambiguous field name '" +
                    fieldName +
                    "' — " +
                    fields.size() +
                    " fields exist with that name across " +
                    "different entities. Provide entityName on the " +
                    "ConsentScope to disambiguate."
            );
        }
        return fields.get(0).getFieldId();
    }

    private Long resolveCategoryId(String categoryName) {
        Category category = categoryRepository
            .findByName(categoryName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No category found with name: " + categoryName
                )
            );
        return category.getCategoryId();
    }

    // -----------------------------------------------------------------------
    // Internal — DTO mapping
    // -----------------------------------------------------------------------

    private ConsentAuditEntry toAuditEntry(ConsentAuditLog log) {
        String fieldName = null;
        String entityName = null;
        String categoryName = null;
        String purposeName = null;

        if (log.getFieldId() != null) {
            Optional<Field> field = fieldRepository
                .dao()
                .findOptionalById(log.getFieldId());
            if (field.isPresent()) {
                fieldName = field.get().getFieldName();
                if (field.get().getEntityId() != null) {
                    entityName = entityRepository
                        .dao()
                        .findOptionalById(field.get().getEntityId())
                        .map(Entity::getEntityName)
                        .orElse(null);
                }
            }
        }

        if (log.getCategoryId() != null) {
            categoryName = categoryRepository
                .dao()
                .findOptionalById(log.getCategoryId())
                .map(Category::getCategoryName)
                .orElse(null);
        }

        if (log.getPurposeId() != null) {
            purposeName = purposeRepository
                .findById(log.getPurposeId())
                .map(Purpose::getPurposeName)
                .orElse(null);
        }

        Instant expiresAt =
            log.getExpiresAt() != null
                ? log.getExpiresAt().toInstant(ZoneOffset.UTC)
                : null;
        Instant timestamp =
            log.getCreatedAt() != null
                ? log.getCreatedAt().toInstant(ZoneOffset.UTC)
                : null;

        Map<String, String> metadata = Collections.emptyMap();

        return new ConsentAuditEntry(
            log.getSubjectId(),
            fieldName,
            entityName,
            categoryName,
            purposeName,
            log.getAction(),
            expiresAt,
            log.getActor(),
            log.getIpAddress(),
            log.getUserAgent(),
            metadata,
            timestamp
        );
    }

    private PolicySummaryEntry toPolicySummary(ConsentPolicy cp) {
        String fieldName = null;
        String entityName = null;
        String categoryName = null;
        String purposeName = null;

        if (cp.getFieldId() != null) {
            Optional<Field> field = fieldRepository
                .dao()
                .findOptionalById(cp.getFieldId());
            if (field.isPresent()) {
                fieldName = field.get().getFieldName();
                if (field.get().getEntityId() != null) {
                    entityName = entityRepository
                        .dao()
                        .findOptionalById(field.get().getEntityId())
                        .map(Entity::getEntityName)
                        .orElse(null);
                }
            }
        }

        if (cp.getCategoryId() != null) {
            categoryName = categoryRepository
                .dao()
                .findOptionalById(cp.getCategoryId())
                .map(Category::getCategoryName)
                .orElse(null);
        }

        if (cp.getPurposeId() != null) {
            purposeName = purposeRepository
                .findById(cp.getPurposeId())
                .map(Purpose::getPurposeName)
                .orElse(null);
        }

        String status = Boolean.TRUE.equals(cp.getGranted())
            ? "GRANTED"
            : "REVOKED";
        Instant expiresAt =
            cp.getExpiresAt() != null
                ? cp.getExpiresAt().toInstant(ZoneOffset.UTC)
                : null;
        Instant createdAt =
            cp.getCreatedAt() != null
                ? cp.getCreatedAt().toInstant(ZoneOffset.UTC)
                : null;

        return new PolicySummaryEntry(
            cp.getSubjectId(),
            fieldName,
            entityName,
            categoryName,
            purposeName,
            status,
            expiresAt,
            createdAt
        );
    }
}
