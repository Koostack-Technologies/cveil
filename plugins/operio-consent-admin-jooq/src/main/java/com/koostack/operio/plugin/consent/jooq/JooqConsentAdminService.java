package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * jOOQ-backed {@link ConsentAdminService} implementation.
 *
 * <p>Resolves string-based {@link ConsentScope} names (field name, category
 * name) into relational IDs and delegates to
 * {@link ConsentPolicyRepository} for the actual upsert.
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
 */
public class JooqConsentAdminService
    implements ConsentAdminService<JooqConsentAdminServiceConfig>
{

    private DSLContext dslContext;
    private EntityRepository entityRepository;
    private FieldRepository fieldRepository;
    private CategoryRepository categoryRepository;
    private FieldCategoryRepository fieldCategoryRepository;
    private ConsentPolicyRepository consentPolicyRepository;

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
        this.dslContext = JooqContextFactory.createContext(
            config,
            "operio-consent-admin-jooq",
            "classpath:db/migration/consent"
        );
        this.entityRepository = new EntityRepository(dslContext);
        this.fieldRepository = new FieldRepository(dslContext);
        this.categoryRepository = new CategoryRepository(dslContext);
        this.fieldCategoryRepository = new FieldCategoryRepository(dslContext);
        this.consentPolicyRepository = new ConsentPolicyRepository(dslContext);
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
    // Internal — upsert delegation
    // -----------------------------------------------------------------------

    /**
     * Resolves the string-based {@link ConsentScope} into relational IDs
     * and delegates to the appropriate {@link ConsentPolicyRepository}
     * upsert method.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope containing field/category names
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

        String purposeName = scope.purposeName();

        if (scope.fieldName() != null) {
            // Field-level write: resolve fieldName → field_id, using
            // entityName from the scope when available to avoid ambiguity.
            Long fieldId = resolveFieldId(
                scope.fieldName(),
                scope.entityName()
            );
            consentPolicyRepository.upsertByField(
                subject,
                fieldId,
                purposeName,
                granted,
                expiresAt
            );
        } else if (scope.category() != null) {
            // Category-level write: resolve category → category_id
            Long categoryId = resolveCategoryId(scope.category());
            consentPolicyRepository.upsertByCategory(
                subject,
                categoryId,
                purposeName,
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
}
