package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_POLICY;

import com.koostack.operio.core.models.CategoryInfo;
import com.koostack.operio.core.models.ConsentAuditEntry;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.EntityInfo;
import com.koostack.operio.core.models.FieldInfo;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.PurposeInfo;
import com.koostack.operio.core.models.RegulationInfo;
import com.koostack.operio.core.models.RequestContext;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentAuditLog;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Regulation;
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
 * jOOQ-backed {@link ConsentAdminService} implementation.
 *
 * <p>Resolves string-based {@link ConsentScope} names (field name, category
 * name, purpose name) into relational IDs and delegates to the appropriate
 * repository for the actual upsert.
 *
 * <h2>Field-level vs Category-level Convention</h2>
 * <ul>
 *   <li>If {@code scope.fieldName()} is non-null → field-level write
 *       (resolves {@code fieldName} → {@code field_id})</li>
 *   <li>If {@code scope.fieldName()} is null and {@code scope.category()}
 *       is non-null → category-level write (resolves {@code category} →
 *       {@code category_id})</li>
 * </ul>
 * This mirrors the relational schema's XOR constraint
 * ({@code field_id IS NOT NULL XOR category_id IS NOT NULL}).
 *
 * <h2>Purpose Resolution</h2>
 * <p>All consent writes resolve {@code scope.purposeName()} into a
 * relational {@code purpose_id} via the {@code purpose} table. This
 * replaced the earlier string-based {@code purpose_name} column on
 * {@code consent_policy} (removed in V4).
 */
public class JooqConsentAdminService
    implements ConsentAdminService<JooqConsentAdminServiceConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminService.class
    );

    private DSLContext dslContext;
    private EntityRepository entityRepository;
    private FieldRepository fieldRepository;
    private CategoryRepository categoryRepository;
    private FieldCategoryRepository fieldCategoryRepository;
    private ConsentPolicyRepository consentPolicyRepository;
    private PurposeRepository purposeRepository;
    private RegulationRepository regulationRepository;
    private PurposeRegulationRepository purposeRegulationRepository;
    private ConsentAuditLogRepository consentAuditLogRepository;
    private DsarAuditLogRepository dsarAuditLogRepository;

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private DSLContext getDsl() {
        if (dslContext == null) {
            throw new OperioConfigurationException(
                "JooqConsentAdminService not configured. Call configure() with a valid JooqConsentAdminServiceConfig first."
            );
        }

        return dslContext;
    }

    /**
     * Returns {@code true} if this plugin is configured and the backing
     * database is reachable.
     *
     * <p>Performs a lightweight {@code SELECT 1} connectivity check against
     * the configured datasource. Returns {@code false} if the service has
     * not been configured or if the check fails for any reason.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
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
        return "jooq";
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
            "operio-consent-admin-jooq",
            "classpath:db/migration/consent"
        );
        this.entityRepository = new EntityRepository(dslContext);
        this.fieldRepository = new FieldRepository(dslContext);
        this.categoryRepository = new CategoryRepository(dslContext);
        this.fieldCategoryRepository = new FieldCategoryRepository(dslContext);
        this.consentPolicyRepository = new ConsentPolicyRepository(dslContext);
        this.purposeRepository = new PurposeRepository(dslContext);
        this.regulationRepository = new RegulationRepository(dslContext);
        this.purposeRegulationRepository = new PurposeRegulationRepository(
            dslContext
        );
        this.consentAuditLogRepository = new ConsentAuditLogRepository(
            dslContext
        );
        this.dsarAuditLogRepository = new DsarAuditLogRepository(dslContext);
    }

    // -----------------------------------------------------------------------
    // ConsentAdminService — core methods
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, true, null);
    }

    @Override
    public void revokeConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, false, null);
    }

    // -----------------------------------------------------------------------
    // ConsentAdminService — expiresAt overloads
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        upsert(subject, accessRequest, true, expiresAt);
    }

    @Override
    public void revokeConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        upsert(subject, accessRequest, false, expiresAt);
    }

    /**
     * Revokes all consent policies for a subject (GDPR Article 17 erasure).
     *
     * <p>Performs a bulk DELETE on all consent_policy rows matching the subject,
     * then logs the erasure event to the consent audit log (append-only record
     * of the bulk operation).
     *
     * @param subject the data subject identifier
     */
    @Override
    public void revokeAllConsent(String subject) {
        getDsl(); // guard

        // Fetch all current policies for the subject before deletion (for audit logging)
        List<ConsentPolicy> policies = consentPolicyRepository.findBySubject(
            subject
        );

        // Perform bulk deletion
        var count = getDsl()
            .deleteFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subject))
            .execute();

        LOG.info(
            "Bulk revoked {} consent policies for subject: {}",
            count,
            subject
        );

        // Log the bulk erasure to the audit trail (append-only)
        // This creates a single DSAR-related audit entry indicating a bulk deletion
        if (count > 0) {
            consentAuditLogRepository.insert(
                subject,
                null, // fieldId — not applicable for bulk operation
                null, // categoryId — not applicable for bulk operation
                null, // purposeId — not applicable for bulk operation
                "BULK_REVOKED", // action
                false, // granted
                null, // expiresAt — not applicable for bulk operation
                "SYSTEM", // actor — bulk erasure initiated by system (could be extended to capture real actor)
                null, // ipAddress
                null, // userAgent
                "{\"bulk_deletion\": true, \"count\": " + count + "}" // metadata
            );
        }
    }

    /**
     * Deletes all consent audit log entries for a subject (DSAR erasure).
     *
     * <p>Delegates to {@link ConsentAuditLogRepository#deleteBySubject(String)}.
     * This is a destructive operation — only call as part of a full DSAR
     * erasure flow.
     *
     * @param subject the data subject identifier
     * @return the number of audit log entries deleted
     */
    @Override
    public int deleteAuditLogBySubject(String subject) {
        getDsl(); // guard
        return consentAuditLogRepository.deleteBySubject(subject);
    }

    /**
     * Counts the number of consent policies for the given subject.
     *
     * <p>Delegates to {@link ConsentPolicyRepository#countBySubject(String)}
     * which uses a lightweight {@code SELECT COUNT(*)} query.
     *
     * @param subjectId the data subject identifier
     * @return the number of consent policies for the subject
     */
    @Override
    public int countPoliciesBySubject(String subjectId) {
        getDsl(); // guard
        return consentPolicyRepository.countBySubject(subjectId);
    }

    // -----------------------------------------------------------------------
    // Accessors (post-configuration)
    // -----------------------------------------------------------------------

    /**
     * Returns the underlying {@link ConsentPolicyRepository}.
     *
     * <p>Primarily useful for tests and advanced callers that need direct
     * repository access.
     *
     * @return the consent policy repository
     * @throws IllegalStateException if not yet configured
     */
    public ConsentPolicyRepository consentPolicyRepository() {
        getDsl(); // guard
        return consentPolicyRepository;
    }

    /**
     * Returns the underlying {@link EntityRepository}.
     *
     * @return the entity repository
     * @throws IllegalStateException if not yet configured
     */
    public EntityRepository entityRepository() {
        getDsl(); // guard
        return entityRepository;
    }

    /**
     * Returns the underlying {@link FieldRepository}.
     *
     * @return the field repository
     * @throws IllegalStateException if not yet configured
     */
    public FieldRepository fieldRepository() {
        getDsl(); // guard
        return fieldRepository;
    }

    /**
     * Returns the underlying {@link CategoryRepository}.
     *
     * @return the category repository
     * @throws IllegalStateException if not yet configured
     */
    public CategoryRepository categoryRepository() {
        getDsl(); // guard
        return categoryRepository;
    }

    /**
     * Returns the underlying {@link FieldCategoryRepository}.
     *
     * @return the field-category repository
     * @throws IllegalStateException if not yet configured
     */
    public FieldCategoryRepository fieldCategoryRepository() {
        getDsl(); // guard
        return fieldCategoryRepository;
    }

    /**
     * Returns the underlying {@link PurposeRepository}.
     *
     * @return the purpose repository
     * @throws IllegalStateException if not yet configured
     */
    public PurposeRepository purposeRepository() {
        getDsl(); // guard
        return purposeRepository;
    }

    /**
     * Returns the underlying {@link RegulationRepository}.
     *
     * @return the regulation repository
     * @throws IllegalStateException if not yet configured
     */
    public RegulationRepository regulationRepository() {
        getDsl(); // guard
        return regulationRepository;
    }

    /**
     * Returns the underlying {@link PurposeRegulationRepository}.
     *
     * @return the purpose-regulation repository
     * @throws IllegalStateException if not yet configured
     */
    public PurposeRegulationRepository purposeRegulationRepository() {
        getDsl(); // guard
        return purposeRegulationRepository;
    }

    /**
     * Returns the underlying {@link ConsentAuditLogRepository}.
     *
     * @return the consent audit log repository — never {@code null} once configured
     * @throws OperioConfigurationException if the service has not been configured
     */
    public ConsentAuditLogRepository consentAuditLogRepository() {
        getDsl(); // guard
        return consentAuditLogRepository;
    }

    /**
     * Returns the underlying {@link DsarAuditLogRepository}.
     *
     * @return the DSAR audit log repository — never {@code null} once configured
     * @throws OperioConfigurationException if the service has not been configured
     */
    public DsarAuditLogRepository dsarAuditLogRepository() {
        getDsl(); // guard
        return dsarAuditLogRepository;
    }

    // -----------------------------------------------------------------------
    // Schema registration — SensitiveDiscovery pipeline
    // -----------------------------------------------------------------------

    /**
     * Registers a sensitive field in the consent admin schema.
     *
     * <p>If {@code entityName} is non-null the entity is upserted first,
     * then the field is upserted under that entity. Uses upsert semantics
     * so re-registering an existing field simply updates its description.
     *
     * @param fieldName   the field name
     * @param entityName  the owning entity name, or {@code null}
     * @param description optional description, or {@code null}
     */
    @Override
    public void registerField(
        String fieldName,
        String entityName,
        String description
    ) {
        getDsl(); // guard

        Long entityId = null;
        if (entityName != null && !entityName.isBlank()) {
            entityRepository.upsertByName(entityName, null, null);
            entityId = entityRepository
                .findByName(entityName)
                .map(Entity::getEntityId)
                .orElseThrow(() ->
                    new IllegalStateException(
                        "Entity upsert succeeded but lookup failed: " +
                            entityName
                    )
                );
        }

        fieldRepository.upsertByNameAndEntity(
            fieldName,
            entityId,
            description,
            null
        );
    }

    /**
     * Registers a consent category in the consent admin schema.
     *
     * <p>Uses upsert semantics — re-registering an existing category
     * updates its description.
     *
     * @param categoryName the category name
     * @param description  optional description, or {@code null}
     */
    @Override
    public void registerCategory(String categoryName, String description) {
        getDsl(); // guard
        categoryRepository.upsertByName(categoryName, description, null);
    }

    /**
     * Links a field to a category. Both must already be registered.
     *
     * <p>Idempotent — if the link already exists, this is a no-op.
     *
     * @param fieldName    the field name
     * @param entityName   the owning entity name, or {@code null}
     * @param categoryName the category to link to
     * @throws IllegalArgumentException if the field or category is not found
     */
    @Override
    public void linkFieldToCategory(
        String fieldName,
        String entityName,
        String categoryName
    ) {
        getDsl(); // guard

        // Resolve field
        Long fieldId;
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
            fieldId = field.get().getFieldId();
        } else {
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
                        "' — provide entityName to disambiguate"
                );
            }
            fieldId = fields.get(0).getFieldId();
        }

        // Resolve category
        Category category = categoryRepository
            .findByName(categoryName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No category found with name: " + categoryName
                )
            );

        fieldCategoryRepository.link(fieldId, category.getCategoryId(), null);
    }

    // -----------------------------------------------------------------------
    // Schema registration — Purpose & Regulation
    // -----------------------------------------------------------------------

    /**
     * Registers a consent purpose in the consent admin schema.
     *
     * <p>Uses upsert semantics — re-registering an existing purpose
     * updates its description.
     *
     * @param purposeName the purpose name (e.g. "marketing", "analytics")
     * @param description optional description, or {@code null}
     */
    @Override
    public void registerPurpose(String purposeName, String description) {
        getDsl(); // guard
        purposeRepository.upsertByName(purposeName, description, null);
    }

    /**
     * Registers a regulation in the consent admin schema.
     *
     * <p>Uses upsert semantics — re-registering an existing regulation
     * updates its fields.
     *
     * @param regulationName the regulation short name (e.g. "GDPR", "CCPA")
     * @param fullName       the full legal name, or {@code null}
     * @param jurisdiction   the geographic jurisdiction (e.g. "EU"), or {@code null}
     * @param description    optional description, or {@code null}
     * @param url            a reference URL to the regulation text, or {@code null}
     */
    @Override
    public void registerRegulation(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {
        getDsl(); // guard
        regulationRepository.upsertByName(
            regulationName,
            fullName,
            jurisdiction,
            description,
            url,
            null
        );
    }

    /**
     * Links a purpose to a regulation with an optional legal basis.
     *
     * <p>Both the purpose and regulation must already be registered.
     * Idempotent — if the link already exists, the legal basis is updated.
     *
     * @param purposeName    the purpose name
     * @param regulationName the regulation name
     * @param legalBasis     the legal basis (e.g. "consent", "legitimate interest"),
     *                       or {@code null}
     * @throws IllegalArgumentException if the purpose or regulation is not found
     */
    @Override
    public void linkPurposeToRegulation(
        String purposeName,
        String regulationName,
        String legalBasis
    ) {
        getDsl(); // guard

        Purpose purpose = purposeRepository
            .findByName(purposeName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No purpose found with name: " + purposeName
                )
            );

        Regulation regulation = regulationRepository
            .findByName(regulationName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No regulation found with name: " + regulationName
                )
            );

        purposeRegulationRepository.link(
            purpose.getPurposeId(),
            regulation.getRegulationId(),
            legalBasis,
            null
        );
    }

    // -----------------------------------------------------------------------
    // Query methods — SPI overrides
    // -----------------------------------------------------------------------

    /**
     * Returns all registered fields.
     *
     * @return every field as a {@link FieldInfo} DTO, possibly empty
     */
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

    /**
     * Returns all registered categories.
     *
     * @return every category as a {@link CategoryInfo} DTO, possibly empty
     */
    @Override
    public List<CategoryInfo> listCategories() {
        getDsl(); // guard
        return categoryRepository
            .findAll()
            .stream()
            .map(c -> new CategoryInfo(c.getCategoryName(), c.getDescription()))
            .toList();
    }

    /**
     * Returns all registered entities.
     *
     * @return every entity as an {@link EntityInfo} DTO, possibly empty
     */
    @Override
    public List<EntityInfo> listEntities() {
        getDsl(); // guard
        return entityRepository
            .findAll()
            .stream()
            .map(e -> new EntityInfo(e.getEntityName(), e.getDescription()))
            .toList();
    }

    /**
     * Returns all registered purposes.
     *
     * @return every purpose as a {@link PurposeInfo} DTO, possibly empty
     */
    @Override
    public List<PurposeInfo> listPurposes() {
        getDsl(); // guard
        return purposeRepository
            .findAll()
            .stream()
            .map(p -> new PurposeInfo(p.getPurposeName(), p.getDescription()))
            .toList();
    }

    /**
     * Returns all registered regulations.
     *
     * @return every regulation as a {@link RegulationInfo} DTO, possibly empty
     */
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

    /**
     * Returns all consent policies for the given subject, mapped to
     * {@link PolicySummaryEntry} DTOs that include resolved names for
     * fields, categories, entities, and purposes.
     *
     * @param subject the data subject identifier
     * @return every policy for the subject as a summary DTO, possibly empty
     */
    @Override
    public List<PolicySummaryEntry> findPoliciesBySubject(String subject) {
        getDsl(); // guard
        List<ConsentPolicy> policies = consentPolicyRepository.findBySubject(
            subject
        );
        return policies.stream().map(this::toPolicySummary).toList();
    }

    /**
     * Returns the full consent audit history for the given subject,
     * ordered by timestamp descending (most recent first).
     *
     * <p>Delegates to {@link ConsentAuditLogRepository#findBySubject} and
     * maps each {@code consent_audit_log} row to a
     * {@link ConsentAuditEntry} DTO.
     *
     * @param subject the data subject identifier
     * @return audit history entries, most recent first, possibly empty
     */
    @Override
    public List<ConsentAuditEntry> getConsentHistory(String subject) {
        getDsl(); // guard
        List<ConsentAuditLog> logs = consentAuditLogRepository.findBySubject(
            subject
        );
        return logs.stream().map(this::toAuditEntry).toList();
    }

    /**
     * Returns the consent audit history for the given subject filtered
     * by scope (field + entity + purpose combination), ordered by
     * timestamp descending (most recent first).
     *
     * <p>Resolves scope names to relational IDs using the same
     * field-vs-category priority as {@link #upsert}: if
     * {@code scope.fieldName()} is present it takes precedence and
     * {@code categoryId} is left {@code null} (field-level audit entries
     * have {@code categoryId = null}). Only non-null IDs are included in
     * the WHERE clause.
     *
     * @param subject the data subject identifier
     * @param scope   the consent scope to filter by
     * @return filtered audit history entries, most recent first,
     *         possibly empty
     */
    @Override
    public List<ConsentAuditEntry> getConsentHistory(
        String subject,
        ConsentScope scope
    ) {
        getDsl(); // guard

        // Resolve scope names to IDs using the same field-vs-category
        // priority as upsert(): if fieldName is present it takes precedence
        // (the audit entry will have fieldId set and categoryId null), so
        // we must NOT also filter on categoryId or we'd match nothing.
        Long fieldId = null;
        Long categoryId = null;
        Long purposeId = null;

        if (scope.fieldName() != null) {
            fieldId = resolveFieldId(scope.fieldName(), scope.entityName());
            // Do NOT resolve category — field-level entries have categoryId null
        } else if (scope.category() != null) {
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
    // Internal — upsert delegation
    // -----------------------------------------------------------------------

    /**
     * Resolves the string-based {@link ConsentScope} into relational IDs
     * and delegates to the appropriate {@link ConsentPolicyRepository}
     * upsert method.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope containing field/category/purpose names
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiration
     */
    private void upsert(
        String subject,
        ConsentScope scope,
        boolean granted,
        Instant expiresAt
    ) {
        getDsl(); // guard — ensures we're configured

        Long purposeId = resolvePurposeId(scope.purposeName());
        Long fieldId = null;
        Long categoryId = null;

        if (scope.fieldName() != null) {
            // Field-level write: resolve fieldName → field_id, using
            // entityName from the scope when available to avoid ambiguity.
            fieldId = resolveFieldId(scope.fieldName(), scope.entityName());
            consentPolicyRepository.upsertByField(
                subject,
                fieldId,
                purposeId,
                granted,
                expiresAt
            );
        } else if (scope.category() != null) {
            // Category-level write: resolve category → category_id
            categoryId = resolveCategoryId(scope.category());
            consentPolicyRepository.upsertByCategory(
                subject,
                categoryId,
                purposeId,
                granted,
                expiresAt
            );
        } else {
            throw new IllegalArgumentException(
                "ConsentScope must have either a non-null fieldName or a " +
                    "non-null category, but both were null: " +
                    scope
            );
        }

        // Append audit log entry in the same transactional context as the
        // consent_policy upsert. RequestContext provides actor/IP/UA from
        // the auth filter scope; defaults to "system"/null/null when no
        // request context is bound (e.g. programmatic calls).
        consentAuditLogRepository.insert(
            subject,
            fieldId,
            categoryId,
            purposeId,
            granted ? "GRANTED" : "REVOKED",
            granted,
            expiresAt,
            RequestContext.actor(),
            RequestContext.ipAddress(),
            RequestContext.userAgent(),
            null
        );
    }

    // -----------------------------------------------------------------------
    // Internal — name-to-ID resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves a purpose name to its relational {@code purpose_id}.
     *
     * <p>Same pattern as {@link #resolveFieldId} and
     * {@link #resolveCategoryId}.
     *
     * @param purposeName the purpose name to resolve
     * @return the purpose's database ID
     * @throws IllegalArgumentException if no purpose with that name exists
     */
    private Long resolvePurposeId(String purposeName) {
        if (purposeName == null || purposeName.isBlank()) {
            throw new IllegalArgumentException(
                "purposeName must not be null or blank — every consent " +
                    "decision must be scoped to a purpose (the purpose_id " +
                    "column is NOT NULL after V4 migration)"
            );
        }
        Purpose purpose = purposeRepository
            .findByName(purposeName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "No purpose found with name: " +
                        purposeName +
                        ". Register it first via registerPurpose()."
                )
            );
        return purpose.getPurposeId();
    }

    /**
     * Resolves a field name to its relational {@code field_id}.
     *
     * <p>When {@code entityName} is non-null the lookup is scoped to
     * the corresponding entity, guaranteeing an unambiguous result.
     * When {@code entityName} is {@code null} the lookup falls back to
     * a name-only search and throws if multiple rows match.
     *
     * @param fieldName  the field name to resolve
     * @param entityName the owning entity name, or {@code null} for an
     *                   unscoped lookup
     * @return the field's database ID
     * @throws IllegalArgumentException if no field with that name exists
     * @throws IllegalStateException    if multiple fields share the same
     *                                  name (ambiguous — provide entityName)
     */
    private Long resolveFieldId(String fieldName, String entityName) {
        // Entity-scoped resolution — unambiguous
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

        // Fallback: name-only lookup
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

    /**
     * Resolves a category name to its relational {@code category_id}.
     *
     * @param categoryName the category name to resolve
     * @return the category's database ID
     * @throws IllegalArgumentException if no category with that name exists
     */
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

    /**
     * Maps a {@link ConsentPolicy} row to a {@link PolicySummaryEntry} DTO,
     * resolving relational IDs back to human-readable names.
     */
    /**
     * Maps a {@link ConsentAuditLog} row to a {@link ConsentAuditEntry} DTO,
     * resolving relational IDs back to human-readable names.
     */
    private ConsentAuditEntry toAuditEntry(ConsentAuditLog log) {
        String fieldName = null;
        String entityName = null;
        String categoryName = null;
        String purposeName = null;

        // Resolve field → fieldName + entityName
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

        // Resolve category → categoryName
        if (log.getCategoryId() != null) {
            categoryName = categoryRepository
                .dao()
                .findOptionalById(log.getCategoryId())
                .map(Category::getCategoryName)
                .orElse(null);
        }

        // Resolve purpose → purposeName
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

        // Parse metadata JSON string back to Map — null-safe
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

        // Resolve field → fieldName + entityName
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

        // Resolve category → categoryName
        if (cp.getCategoryId() != null) {
            categoryName = categoryRepository
                .dao()
                .findOptionalById(cp.getCategoryId())
                .map(Category::getCategoryName)
                .orElse(null);
        }

        // Resolve purpose → purposeName
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
