package com.koostack.operio.plugin.consent.sync.embedded;

import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentRepository;
import com.koostack.operio.plugin.consent.caffeine.CaffeineFieldCategoryMappingRepository;
import com.koostack.operio.plugin.consent.jooq.CategoryRepository;
import com.koostack.operio.plugin.consent.jooq.ConsentPolicyRepository;
import com.koostack.operio.plugin.consent.jooq.EntityRepository;
import com.koostack.operio.plugin.consent.jooq.FieldCategoryRepository;
import com.koostack.operio.plugin.consent.jooq.FieldRepository;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminService;
import com.koostack.operio.plugin.consent.jooq.PurposeRepository;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rehydrates/reconciles the in-process Caffeine consent cache from the
 * relational database (source of truth).
 *
 * <p>The helper is designed to be called:
 * <ul>
 *   <li><strong>At startup</strong> — to warm the cache from the DB so that
 *       the first consent evaluation does not return all-denied.</li>
 *   <li><strong>Periodically</strong> — to correct drift caused by cache
 *       evictions, TTL expiry, or out-of-band DB changes.</li>
 *   <li><strong>On demand</strong> — e.g. after a bulk data import or
 *       administrative operation that bypassed the sync service.</li>
 * </ul>
 *
 * <h2>Two Datasets</h2>
 * <p>The helper rebuilds two independent datasets in the Caffeine layer:
 * <ol>
 *   <li><strong>Field ↔ Category mappings</strong> — populated from the
 *       {@code field_category}, {@code field}, {@code entity}, and
 *       {@code category} tables. Each mapping becomes a call to
 *       {@link CaffeineFieldCategoryMappingRepository#addFieldToCategory}.
 *       Qualified field strings use the {@code {entity}/{field}} format
 *       defined by {@link CaffeineConsentRepository#qualifyField}.</li>
 *   <li><strong>Consent policies</strong> — populated from
 *       {@code consent_policy} rows. Field-level policies produce a single
 *       cache entry via
 *       {@link CaffeineConsentRepository#setConsent}. Category-level
 *       policies fan out to all fields in the category via
 *       {@link CaffeineConsentRepository#setCategoryConsentForFields},
 *       using the mapping repository to resolve category → fields.</li>
 * </ol>
 *
 * <h2>Ordering</h2>
 * <p>Mappings are always rebuilt <em>before</em> consent policies so that
 * category-level fan-out has the complete set of fields available.
 *
 * <h2>Strategy</h2>
 * <p>Callers choose a {@link ReconciliationStrategy}:
 * <ul>
 *   <li>{@link ReconciliationStrategy#FULL_REBUILD} — clears the cache
 *       before repopulating. Guarantees an exact DB mirror but introduces
 *       a brief read gap.</li>
 *   <li>{@link ReconciliationStrategy#MERGE} — writes DB state into the
 *       cache without clearing. No read gap but stale entries may
 *       linger.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>Individual policy rows that cannot be processed (e.g. orphaned foreign
 * keys, deleted metadata) are logged and counted in
 * {@link ReconciliationResult#policiesSkipped()} /
 * {@link ReconciliationResult#errors()} but do <em>not</em> abort the
 * reconciliation. The helper always processes as many rows as possible.
 *
 * <h2>Thread Safety</h2>
 * <p>The helper itself is stateless (apart from the logger) and is safe to
 * call from any thread. The underlying Caffeine cache and mapping
 * repository are thread-safe by design.
 *
 * @see ReconciliationStrategy
 * @see ReconciliationResult
 * @see EmbeddedConsentSyncServiceImpl#reconcileFromDb()
 */
public class CaffeineReconciliationHelper {

    private static final Logger log = Logger.getLogger(
        CaffeineReconciliationHelper.class.getName()
    );

    // -- Dependencies (all injected via constructor) -------------------------

    private final ConsentPolicyRepository consentPolicyRepo;
    private final FieldRepository fieldRepo;
    private final EntityRepository entityRepo;
    private final CategoryRepository categoryRepo;
    private final FieldCategoryRepository fieldCategoryRepo;
    private final PurposeRepository purposeRepo;
    private final CaffeineConsentRepository caffeineConsentRepo;
    private final CaffeineFieldCategoryMappingRepository caffeineMappingRepo;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates a reconciliation helper wired to the given jOOQ admin service
     * and Caffeine repositories.
     *
     * <p>The admin service is used to access the relational repositories
     * (field, entity, category, field_category, consent_policy). The
     * Caffeine repositories are the write targets.
     *
     * @param jooqAdmin         the jOOQ admin service (source of truth)
     * @param caffeineConsentRepo the Caffeine consent cache to populate
     * @param caffeineMappingRepo the Caffeine field-category mapping cache
     *                            to populate
     * @throws NullPointerException if any argument is {@code null}
     */
    public CaffeineReconciliationHelper(
        JooqConsentAdminService jooqAdmin,
        CaffeineConsentRepository caffeineConsentRepo,
        CaffeineFieldCategoryMappingRepository caffeineMappingRepo
    ) {
        Objects.requireNonNull(jooqAdmin, "jooqAdmin must not be null");
        Objects.requireNonNull(
            caffeineConsentRepo,
            "caffeineConsentRepo must not be null"
        );
        Objects.requireNonNull(
            caffeineMappingRepo,
            "caffeineMappingRepo must not be null"
        );

        this.consentPolicyRepo = jooqAdmin.consentPolicyRepository();
        this.fieldRepo = jooqAdmin.fieldRepository();
        this.entityRepo = jooqAdmin.entityRepository();
        this.categoryRepo = jooqAdmin.categoryRepository();
        this.fieldCategoryRepo = jooqAdmin.fieldCategoryRepository();
        this.purposeRepo = jooqAdmin.purposeRepository();
        this.caffeineConsentRepo = caffeineConsentRepo;
        this.caffeineMappingRepo = caffeineMappingRepo;
    }

    // -----------------------------------------------------------------------
    // Public API — full reconciliation
    // -----------------------------------------------------------------------

    /**
     * Reconciles the entire Caffeine cache from the DB using the default
     * strategy ({@link ReconciliationStrategy#FULL_REBUILD}).
     *
     * @return the reconciliation result with counts and elapsed time
     */
    public ReconciliationResult reconcile() {
        return reconcile(ReconciliationStrategy.FULL_REBUILD);
    }

    /**
     * Reconciles the entire Caffeine cache from the DB using the specified
     * strategy.
     *
     * <p>Processing order:
     * <ol>
     *   <li>If {@link ReconciliationStrategy#FULL_REBUILD}: clear both
     *       caches.</li>
     *   <li>Rebuild field↔category mappings from the
     *       {@code field_category} table.</li>
     *   <li>Load consent policies from the {@code consent_policy} table
     *       and write them into the Caffeine consent cache.</li>
     * </ol>
     *
     * @param strategy the reconciliation strategy to use
     * @return the reconciliation result with counts and elapsed time
     * @throws NullPointerException if {@code strategy} is {@code null}
     */
    public ReconciliationResult reconcile(ReconciliationStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");

        log.log(
            Level.INFO,
            "Starting full reconciliation with strategy={0}",
            strategy
        );

        ReconciliationResult.Builder result = new ReconciliationResult.Builder(
            strategy
        );

        // Step 0: optionally clear caches
        if (strategy == ReconciliationStrategy.FULL_REBUILD) {
            caffeineConsentRepo.invalidateAll();
            caffeineMappingRepo.clear();
            log.fine("Caches cleared (FULL_REBUILD)");
        }

        // Step 1: build lookup maps for metadata resolution
        Map<Long, String> entityNames = buildEntityNameMap();
        Map<Long, Field> fieldMap = buildFieldMap();
        Map<Long, String> categoryNames = buildCategoryNameMap();
        Map<Long, String> purposeNames = buildPurposeNameMap();

        // Step 2: rebuild field↔category mappings
        int mappingsLoaded = rebuildMappings(
            fieldMap,
            entityNames,
            categoryNames
        );
        result.addMappingsLoaded(mappingsLoaded);

        // Step 3: load consent policies
        List<ConsentPolicy> policies = consentPolicyRepo.findAll();
        log.log(
            Level.FINE,
            "Loaded {0} consent policy rows from DB",
            policies.size()
        );

        for (ConsentPolicy policy : policies) {
            processPolicy(
                policy,
                fieldMap,
                entityNames,
                categoryNames,
                purposeNames,
                result
            );
        }

        ReconciliationResult finalResult = result.build();
        log.log(Level.INFO, finalResult.summary());
        return finalResult;
    }

    // -----------------------------------------------------------------------
    // Public API — subject-scoped reconciliation
    // -----------------------------------------------------------------------

    /**
     * Reconciles consent data for a single subject using the default
     * strategy ({@link ReconciliationStrategy#FULL_REBUILD}).
     *
     * <p>When using {@link ReconciliationStrategy#FULL_REBUILD}, only
     * the subject's consent entries are cleared from the cache —
     * field↔category mappings and other subjects' entries are
     * <strong>not</strong> affected.
     *
     * <p>Note: the "clear" step for a single subject uses
     * {@link CaffeineConsentRepository#removeAllConsent(String)} which
     * removes all consent entries for that subject. Mappings are always
     * rebuilt (additively) since they are global, not per-subject.
     *
     * @param subjectId the subject whose consent entries to reconcile
     * @return the reconciliation result
     * @throws NullPointerException if {@code subjectId} is {@code null}
     */
    public ReconciliationResult reconcileSubject(String subjectId) {
        return reconcileSubject(subjectId, ReconciliationStrategy.FULL_REBUILD);
    }

    /**
     * Reconciles consent data for a single subject using the specified
     * strategy.
     *
     * <p>Processing order:
     * <ol>
     *   <li>If {@link ReconciliationStrategy#FULL_REBUILD}: remove all
     *       consent entries for the subject from the cache.</li>
     *   <li>Rebuild field↔category mappings from the
     *       {@code field_category} table (always additive — mappings are
     *       global, not per-subject).</li>
     *   <li>Load consent policies for the subject and write them into the
     *       cache.</li>
     * </ol>
     *
     * @param subjectId the subject whose consent entries to reconcile
     * @param strategy  the reconciliation strategy to use
     * @return the reconciliation result
     * @throws NullPointerException if any argument is {@code null}
     */
    public ReconciliationResult reconcileSubject(
        String subjectId,
        ReconciliationStrategy strategy
    ) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");

        log.log(
            Level.INFO,
            "Starting subject reconciliation for subject={0} strategy={1}",
            new Object[] { subjectId, strategy }
        );

        ReconciliationResult.Builder result = new ReconciliationResult.Builder(
            strategy
        );

        // Step 0: optionally clear the subject's consent entries
        if (strategy == ReconciliationStrategy.FULL_REBUILD) {
            long removed = caffeineConsentRepo.removeAllConsent(subjectId);
            log.log(
                Level.FINE,
                "Removed {0} cache entries for subject={1} (FULL_REBUILD)",
                new Object[] { removed, subjectId }
            );
        }

        // Step 1: build lookup maps
        Map<Long, String> entityNames = buildEntityNameMap();
        Map<Long, Field> fieldMap = buildFieldMap();
        Map<Long, String> categoryNames = buildCategoryNameMap();
        Map<Long, String> purposeNames = buildPurposeNameMap();

        // Step 2: rebuild mappings (additive — global, not per-subject)
        int mappingsLoaded = rebuildMappings(
            fieldMap,
            entityNames,
            categoryNames
        );
        result.addMappingsLoaded(mappingsLoaded);

        // Step 3: load and process the subject's policies
        List<ConsentPolicy> policies = consentPolicyRepo.findAllBySubject(
            subjectId
        );
        log.log(
            Level.FINE,
            "Loaded {0} consent policy rows for subject={1}",
            new Object[] { policies.size(), subjectId }
        );

        for (ConsentPolicy policy : policies) {
            processPolicy(
                policy,
                fieldMap,
                entityNames,
                categoryNames,
                purposeNames,
                result
            );
        }

        ReconciliationResult finalResult = result.build();
        log.log(Level.INFO, finalResult.summary());
        return finalResult;
    }

    // -----------------------------------------------------------------------
    // Internal — metadata lookup maps
    // -----------------------------------------------------------------------

    /**
     * Builds a map of entity_id → entity_name for all entities.
     */
    private Map<Long, String> buildEntityNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Entity entity : entityRepo.findAll()) {
            map.put(entity.getEntityId(), entity.getEntityName());
        }
        return map;
    }

    /**
     * Builds a map of field_id → Field POJO for all fields.
     */
    private Map<Long, Field> buildFieldMap() {
        Map<Long, Field> map = new HashMap<>();
        for (Field field : fieldRepo.findAll()) {
            map.put(field.getFieldId(), field);
        }
        return map;
    }

    /**
     * Builds a map of category_id → category_name for all categories.
     */
    private Map<Long, String> buildCategoryNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Category category : categoryRepo.findAll()) {
            map.put(category.getCategoryId(), category.getCategoryName());
        }
        return map;
    }

    /**
     * Builds a map of purpose_id → purpose_name for all purposes.
     */
    private Map<Long, String> buildPurposeNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Purpose purpose : purposeRepo.findAll()) {
            map.put(purpose.getPurposeId(), purpose.getPurposeName());
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Internal — mapping rebuild
    // -----------------------------------------------------------------------

    /**
     * Rebuilds field↔category mappings in the Caffeine mapping repository
     * from the {@code field_category} table.
     *
     * <p>For each join row, the field's entity name is resolved (or the
     * default sentinel is used for entity-less fields), and
     * {@link CaffeineFieldCategoryMappingRepository#addFieldToCategory} is
     * called. The operation is idempotent.
     *
     * @param fieldMap      field_id → Field POJO
     * @param entityNames   entity_id → entity_name
     * @param categoryNames category_id → category_name
     * @return the number of mappings written
     */
    private int rebuildMappings(
        Map<Long, Field> fieldMap,
        Map<Long, String> entityNames,
        Map<Long, String> categoryNames
    ) {
        List<FieldCategory> links = fieldCategoryRepo.findAll();
        int count = 0;

        for (FieldCategory link : links) {
            try {
                Field field = fieldMap.get(link.getFieldId());
                String categoryName = categoryNames.get(link.getCategoryId());

                if (field == null || categoryName == null) {
                    log.log(
                        Level.WARNING,
                        "Skipping field_category link (field_id={0}, " +
                            "category_id={1}): unresolvable metadata " +
                            "(field={2}, category={3})",
                        new Object[] {
                            link.getFieldId(),
                            link.getCategoryId(),
                            field != null ? field.getFieldName() : "null",
                            categoryName,
                        }
                    );
                    continue;
                }

                String entityName =
                    field.getEntityId() != null
                        ? entityNames.get(field.getEntityId())
                        : null;

                caffeineMappingRepo.addFieldToCategory(
                    entityName,
                    field.getFieldName(),
                    categoryName
                );
                count++;
            } catch (Exception e) {
                log.log(
                    Level.WARNING,
                    "Error rebuilding mapping for field_category link " +
                        "(field_id={0}, category_id={1}): {2}",
                    new Object[] {
                        link.getFieldId(),
                        link.getCategoryId(),
                        e.getMessage(),
                    }
                );
            }
        }

        log.log(
            Level.FINE,
            "Rebuilt {0} field-category mappings from {1} DB rows",
            new Object[] { count, links.size() }
        );
        return count;
    }

    // -----------------------------------------------------------------------
    // Internal — policy processing
    // -----------------------------------------------------------------------

    /**
     * Processes a single consent policy row, writing the appropriate
     * entry/entries into the Caffeine consent cache.
     *
     * <p>Field-level policies ({@code field_id != null}) produce a single
     * cache entry with source {@link CaffeineConsentRepository#SOURCE_FIELD}.
     *
     * <p>Category-level policies ({@code category_id != null}) fan out
     * to all fields in the category using
     * {@link CaffeineConsentRepository#setCategoryConsentForFields},
     * producing one cache entry per mapped field.
     *
     * @param policy        the consent policy row
     * @param fieldMap      field_id → Field POJO lookup
     * @param entityNames   entity_id → entity_name lookup
     * @param categoryNames category_id → category_name lookup
     * @param result        the mutable result builder to update
     */
    private void processPolicy(
        ConsentPolicy policy,
        Map<Long, Field> fieldMap,
        Map<Long, String> entityNames,
        Map<Long, String> categoryNames,
        Map<Long, String> purposeNames,
        ReconciliationResult.Builder result
    ) {
        try {
            Instant expiresAt = toInstant(policy.getExpiresAt());
            boolean granted = Boolean.TRUE.equals(policy.getGranted());
            String subjectId = policy.getSubjectId();
            String purposeName = purposeNames.get(policy.getPurposeId());

            if (policy.getFieldId() != null) {
                // -- Field-level policy --
                processFieldPolicy(
                    policy,
                    fieldMap,
                    entityNames,
                    subjectId,
                    purposeName,
                    granted,
                    expiresAt,
                    result
                );
            } else if (policy.getCategoryId() != null) {
                // -- Category-level policy --
                processCategoryPolicy(
                    policy,
                    categoryNames,
                    subjectId,
                    purposeName,
                    granted,
                    expiresAt,
                    result
                );
            } else {
                // Neither field nor category — should not happen given the
                // CHECK constraint, but handle defensively
                log.log(
                    Level.WARNING,
                    "Skipping consent policy {0}: both field_id and " +
                        "category_id are null",
                    policy.getConsentPolicyId()
                );
                result.addSkipped();
            }
        } catch (Exception e) {
            log.log(
                Level.WARNING,
                "Error processing consent policy id={0}: {1}",
                new Object[] { policy.getConsentPolicyId(), e.getMessage() }
            );
            result.addError();
        }
    }

    /**
     * Writes a field-level consent policy into the Caffeine cache.
     */
    private void processFieldPolicy(
        ConsentPolicy policy,
        Map<Long, Field> fieldMap,
        Map<Long, String> entityNames,
        String subjectId,
        String purposeName,
        boolean granted,
        Instant expiresAt,
        ReconciliationResult.Builder result
    ) {
        Field field = fieldMap.get(policy.getFieldId());
        if (field == null) {
            log.log(
                Level.WARNING,
                "Skipping field-level policy id={0}: field_id={1} not " +
                    "found in field table",
                new Object[] {
                    policy.getConsentPolicyId(),
                    policy.getFieldId(),
                }
            );
            result.addSkipped();
            return;
        }

        String entityName =
            field.getEntityId() != null
                ? entityNames.get(field.getEntityId())
                : null;

        caffeineConsentRepo.setConsent(
            subjectId,
            entityName,
            field.getFieldName(),
            purposeName,
            granted,
            CaffeineConsentRepository.SOURCE_FIELD,
            expiresAt
        );

        result.addFieldPolicy();
    }

    /**
     * Processes a category-level consent policy by fanning out to all
     * fields in the category.
     */
    private void processCategoryPolicy(
        ConsentPolicy policy,
        Map<Long, String> categoryNames,
        String subjectId,
        String purposeName,
        boolean granted,
        Instant expiresAt,
        ReconciliationResult.Builder result
    ) {
        String categoryName = categoryNames.get(policy.getCategoryId());
        if (categoryName == null) {
            log.log(
                Level.WARNING,
                "Skipping category-level policy id={0}: category_id={1} " +
                    "not found in category table",
                new Object[] {
                    policy.getConsentPolicyId(),
                    policy.getCategoryId(),
                }
            );
            result.addSkipped();
            return;
        }

        Set<String> qualifiedFields = caffeineMappingRepo.getFieldsForCategory(
            categoryName
        );

        if (qualifiedFields.isEmpty()) {
            log.log(
                Level.FINE,
                "Category-level policy id={0} for category ''{1}'' has " +
                    "no mapped fields — writing 0 fan-out entries",
                new Object[] { policy.getConsentPolicyId(), categoryName }
            );
            // Still count it as a processed category policy with 0 fan-out
            result.addCategoryPolicy(0);
            return;
        }

        caffeineConsentRepo.setCategoryConsentForFields(
            subjectId,
            purposeName,
            granted,
            expiresAt,
            qualifiedFields
        );

        result.addCategoryPolicy(qualifiedFields.size());
    }

    // -----------------------------------------------------------------------
    // Internal — utility
    // -----------------------------------------------------------------------

    /**
     * Converts a {@link LocalDateTime} (stored in UTC in the DB) to an
     * {@link Instant}, or returns {@code null} if the input is {@code null}.
     *
     * @param ldt the local date-time in UTC, or {@code null}
     * @return the corresponding instant, or {@code null}
     */
    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
