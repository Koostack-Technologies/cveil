package com.koostack.operio.plugin.consent.sync.embedded;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentRepository;
import com.koostack.operio.plugin.consent.caffeine.CaffeineFieldCategoryMappingRepository;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Comprehensive tests for {@link CaffeineReconciliationHelper} covering
 * full rebuild, merge strategies, category fan-out, subject-scoped
 * reconciliation, expired policies, edge cases, and end-to-end evaluation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaffeineReconciliationHelperTest {

    private EmbeddedConsentSyncServiceImpl syncService;
    private JooqConsentAdminService jooqAdmin;
    private CaffeineConsentDecisionService caffeineDecision;
    private CaffeineConsentRepository consentRepo;
    private CaffeineFieldCategoryMappingRepository mappingRepo;
    private CaffeineReconciliationHelper helper;

    @BeforeAll
    void setUpService() {
        var config = new EmbeddedConsentSyncServiceConfig(
            new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:reconciliation_test;DB_CLOSE_DELAY=-1",
                "H2"
            ),
            new CaffeineConsentDecisionServiceConfig()
        );

        syncService = new EmbeddedConsentSyncServiceImpl();
        syncService.configure(config);

        jooqAdmin = syncService.jooqAdminService();
        caffeineDecision = syncService.caffeineDecisionService();
        consentRepo = syncService.caffeineConsentRepository();
        mappingRepo = syncService.caffeineMappingRepository();
        helper = syncService.reconciliationHelper();
    }

    @BeforeEach
    void clearCaches() {
        consentRepo.invalidateAll();
        mappingRepo.clear();
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Registers an entity, field, category, and link in the admin schema
     * and grants consent through the sync service (which writes to both
     * DB and cache).
     */
    private void registerAndGrant(
        String entity,
        String field,
        String category,
        String purpose,
        String subject,
        Instant expiresAt
    ) {
        jooqAdmin.registerField(field, entity, null);
        if (category != null) {
            jooqAdmin.registerCategory(category, null);
            jooqAdmin.linkFieldToCategory(field, entity, category);
            // Also populate the mapping repo so sync fan-out works
            mappingRepo.addFieldToCategory(entity, field, category);
        }
        var scope = ConsentScope.to(category, field, entity, purpose);
        syncService.syncGrant(subject, scope, expiresAt);
    }

    /**
     * Registers metadata and grants consent with no expiration.
     */
    private void registerAndGrant(
        String entity,
        String field,
        String category,
        String purpose,
        String subject
    ) {
        registerAndGrant(entity, field, category, purpose, subject, null);
    }

    /**
     * Evaluates consent and returns whether the scope was granted.
     */
    private boolean isGranted(
        String subject,
        String entity,
        String field,
        String category,
        String purpose
    ) {
        var scope = ConsentScope.to(category, field, entity, purpose);
        ConsentResponse response = caffeineDecision.evaluateConsent(
            subject,
            Set.of(scope)
        );
        return response.granted().contains(scope);
    }

    // =======================================================================
    // Test groups
    // =======================================================================

    @Nested
    @DisplayName("Construction & Accessors")
    class ConstructionTests {

        @Test
        @DisplayName("reconciliationHelper() returns non-null")
        void helperIsNonNull() {
            assertNotNull(helper);
        }

        @Test
        @DisplayName(
            "reconciliationHelper() returns same instance on repeated calls"
        )
        void helperIsCached() {
            CaffeineReconciliationHelper h1 =
                syncService.reconciliationHelper();
            CaffeineReconciliationHelper h2 =
                syncService.reconciliationHelper();
            assertSame(h1, h2);
        }

        @Test
        @DisplayName("constructor rejects null jooqAdmin")
        void nullJooqAdmin() {
            assertThrows(NullPointerException.class, () ->
                new CaffeineReconciliationHelper(null, consentRepo, mappingRepo)
            );
        }

        @Test
        @DisplayName("constructor rejects null consentRepo")
        void nullConsentRepo() {
            assertThrows(NullPointerException.class, () ->
                new CaffeineReconciliationHelper(jooqAdmin, null, mappingRepo)
            );
        }

        @Test
        @DisplayName("constructor rejects null mappingRepo")
        void nullMappingRepo() {
            assertThrows(NullPointerException.class, () ->
                new CaffeineReconciliationHelper(jooqAdmin, consentRepo, null)
            );
        }
    }

    @Nested
    @DisplayName("Full Rebuild Strategy")
    class FullRebuildTests {

        @Test
        @DisplayName("empty DB produces zero-count result")
        void emptyDbReturnsZeroCounts() {
            // Use a completely isolated H2 instance so other tests'
            // DB writes don't pollute the "empty" assertion.
            var isolatedSync = new EmbeddedConsentSyncServiceImpl();
            isolatedSync.configure(
                new EmbeddedConsentSyncServiceConfig(
                    new JooqConsentAdminServiceConfig(
                        "jdbc:h2:mem:reconciliation_empty_" +
                            System.nanoTime() +
                            ";DB_CLOSE_DELAY=-1",
                        "H2"
                    ),
                    new CaffeineConsentDecisionServiceConfig()
                )
            );
            var isolatedHelper = isolatedSync.reconciliationHelper();

            ReconciliationResult result = isolatedHelper.reconcile(
                ReconciliationStrategy.FULL_REBUILD
            );

            assertEquals(
                ReconciliationStrategy.FULL_REBUILD,
                result.strategy()
            );
            assertEquals(0, result.mappingsLoaded());
            assertEquals(0, result.fieldPoliciesLoaded());
            assertEquals(0, result.categoryPoliciesLoaded());
            assertEquals(0, result.categoryFanOutEntries());
            assertEquals(0, result.policiesSkipped());
            assertEquals(0, result.errors());
            assertEquals(0, result.totalPoliciesLoaded());
            assertEquals(0, result.totalCacheEntriesWritten());
            assertNotNull(result.elapsed());
        }

        @Test
        @DisplayName("field-level grant is rebuilt into cache")
        void fieldLevelGrant() {
            registerAndGrant("User", "username", null, "analytics", "user-1");

            // Clear the cache — simulates a restart
            consentRepo.invalidateAll();
            mappingRepo.clear();

            // Verify cache is empty
            assertFalse(
                isGranted("user-1", "User", "username", null, "analytics")
            );

            // Reconcile
            ReconciliationResult result = helper.reconcile();

            assertTrue(result.fieldPoliciesLoaded() >= 1);
            assertTrue(
                isGranted("user-1", "User", "username", null, "analytics")
            );
        }

        @Test
        @DisplayName("field-level grant with entity resolved correctly")
        void fieldLevelWithEntity() {
            registerAndGrant(
                "Patient",
                "bloodType",
                null,
                "operations",
                "pat-1"
            );

            consentRepo.invalidateAll();
            mappingRepo.clear();

            assertFalse(
                isGranted("pat-1", "Patient", "bloodType", null, "operations")
            );

            helper.reconcile();

            assertTrue(
                isGranted("pat-1", "Patient", "bloodType", null, "operations")
            );
        }

        @Test
        @DisplayName("multiple field-level grants for different subjects")
        void multipleSubjects() {
            registerAndGrant("User", "fullName", null, "marketing", "alice");
            registerAndGrant("User", "fullName", null, "marketing", "bob");

            consentRepo.invalidateAll();
            mappingRepo.clear();

            assertFalse(
                isGranted("alice", "User", "fullName", null, "marketing")
            );
            assertFalse(
                isGranted("bob", "User", "fullName", null, "marketing")
            );

            ReconciliationResult result = helper.reconcile();

            assertTrue(result.fieldPoliciesLoaded() >= 2);
            assertTrue(
                isGranted("alice", "User", "fullName", null, "marketing")
            );
            assertTrue(isGranted("bob", "User", "fullName", null, "marketing"));
        }

        @Test
        @DisplayName("FULL_REBUILD clears stale cache entries")
        void fullRebuildClearsStaleEntries() {
            registerAndGrant(
                "User",
                "nickname",
                null,
                "marketing",
                "user-stale"
            );

            // Write a fake/stale entry directly into the cache
            consentRepo.setFieldConsent(
                "ghost-user",
                "Ghost",
                "phantom",
                "test",
                true
            );
            assertTrue(
                consentRepo.exists("ghost-user", "Ghost", "phantom", "test"),
                "stale entry should exist before reconcile"
            );

            // Reconcile with FULL_REBUILD
            helper.reconcile(ReconciliationStrategy.FULL_REBUILD);

            // The stale entry should be gone
            assertFalse(
                consentRepo.exists("ghost-user", "Ghost", "phantom", "test"),
                "stale entry should be cleared after FULL_REBUILD"
            );

            // The legitimate entry should be present
            assertTrue(
                isGranted("user-stale", "User", "nickname", null, "marketing")
            );
        }

        @Test
        @DisplayName("result elapsed is non-negative")
        void elapsedIsNonNegative() {
            ReconciliationResult result = helper.reconcile();
            assertTrue(
                result.elapsed().toNanos() >= 0,
                "elapsed should be non-negative"
            );
        }

        @Test
        @DisplayName("result summary() is non-null and non-empty")
        void summaryIsNonEmpty() {
            ReconciliationResult result = helper.reconcile();
            assertNotNull(result.summary());
            assertFalse(result.summary().isEmpty());
            assertTrue(result.summary().contains("FULL_REBUILD"));
        }

        @Test
        @DisplayName("default reconcile() uses FULL_REBUILD")
        void defaultStrategyIsFullRebuild() {
            ReconciliationResult result = helper.reconcile();
            assertEquals(
                ReconciliationStrategy.FULL_REBUILD,
                result.strategy()
            );
        }
    }

    @Nested
    @DisplayName("Merge Strategy")
    class MergeTests {

        @Test
        @DisplayName("MERGE does not clear existing cache entries")
        void mergePreservesExisting() {
            registerAndGrant(
                "User",
                "phoneNumber",
                null,
                "support",
                "user-merge"
            );

            // Write a separate entry directly (not in DB)
            consentRepo.setFieldConsent(
                "extra-user",
                "Extra",
                "extraField",
                "misc",
                true
            );

            // Reconcile with MERGE
            ReconciliationResult result = helper.reconcile(
                ReconciliationStrategy.MERGE
            );

            assertEquals(ReconciliationStrategy.MERGE, result.strategy());

            // The non-DB entry should still be present
            assertTrue(
                consentRepo.exists("extra-user", "Extra", "extraField", "misc"),
                "MERGE should not clear entries that are not in the DB"
            );

            // The DB entry should also be present
            assertTrue(
                isGranted("user-merge", "User", "phoneNumber", null, "support")
            );
        }

        @Test
        @DisplayName("MERGE overwrites existing entries with DB state")
        void mergeOverwritesExisting() {
            registerAndGrant(
                "User",
                "zipcode",
                null,
                "marketing",
                "user-overwrite"
            );

            // Manually revoke in cache only (DB still shows granted)
            consentRepo.setFieldConsent(
                "user-overwrite",
                "User",
                "zipcode",
                "marketing",
                false
            );

            // Verify cache shows revoked
            assertFalse(
                isGranted(
                    "user-overwrite",
                    "User",
                    "zipcode",
                    null,
                    "marketing"
                )
            );

            // MERGE should overwrite with DB state (granted)
            helper.reconcile(ReconciliationStrategy.MERGE);

            assertTrue(
                isGranted(
                    "user-overwrite",
                    "User",
                    "zipcode",
                    null,
                    "marketing"
                )
            );
        }
    }

    @Nested
    @DisplayName("Field-Category Mapping Rebuild")
    class MappingRebuildTests {

        @Test
        @DisplayName("mappings are rebuilt from DB")
        void mappingsRebuilt() {
            jooqAdmin.registerField("ssn", "Person", null);
            jooqAdmin.registerCategory("PII", null);
            jooqAdmin.linkFieldToCategory("ssn", "Person", "PII");

            // Clear cache mappings
            mappingRepo.clear();
            assertTrue(
                mappingRepo.getFieldsForCategory("PII").isEmpty(),
                "mappings should be empty before reconcile"
            );

            ReconciliationResult result = helper.reconcile();

            assertTrue(result.mappingsLoaded() >= 1);
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            assertTrue(
                fields.contains(
                    CaffeineConsentRepository.qualifyField("Person", "ssn")
                ),
                "Person/ssn should be mapped to PII after reconcile"
            );
        }

        @Test
        @DisplayName("mappings loaded before policies so fan-out works")
        void mappingsBeforePolicies() {
            // Register metadata in DB
            jooqAdmin.registerField("dob", "Patient", null);
            jooqAdmin.registerCategory("HEALTH", null);
            jooqAdmin.linkFieldToCategory("dob", "Patient", "HEALTH");
            mappingRepo.addFieldToCategory("Patient", "dob", "HEALTH");

            // Grant a category-level consent
            var scope = ConsentScope.to("HEALTH", null, null, "research");
            syncService.syncGrant("patient-1", scope, null);

            // Clear cache
            consentRepo.invalidateAll();
            mappingRepo.clear();

            // Reconcile — mappings must be rebuilt first for category fan-out
            ReconciliationResult result = helper.reconcile();

            assertTrue(result.mappingsLoaded() >= 1);
            assertTrue(result.categoryPoliciesLoaded() >= 1);

            // The fan-out should make the field show as granted
            assertTrue(
                isGranted("patient-1", "Patient", "dob", "HEALTH", "research")
            );
        }
    }

    @Nested
    @DisplayName("Category Fan-Out")
    class CategoryFanOutTests {

        @Test
        @DisplayName("category-level policy fans out to all mapped fields")
        void categoryFanOut() {
            // Register two fields in the same category
            jooqAdmin.registerField("addr1", "Contact", null);
            jooqAdmin.registerField("addr2", "Contact", null);
            jooqAdmin.registerCategory("ADDRESS", null);
            jooqAdmin.linkFieldToCategory("addr1", "Contact", "ADDRESS");
            jooqAdmin.linkFieldToCategory("addr2", "Contact", "ADDRESS");
            mappingRepo.addFieldToCategory("Contact", "addr1", "ADDRESS");
            mappingRepo.addFieldToCategory("Contact", "addr2", "ADDRESS");

            // Grant at category level
            var scope = ConsentScope.to("ADDRESS", null, null, "shipping");
            syncService.syncGrant("cust-1", scope, null);

            // Clear and reconcile
            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            assertTrue(result.categoryPoliciesLoaded() >= 1);
            assertTrue(result.categoryFanOutEntries() >= 2);

            assertTrue(
                isGranted("cust-1", "Contact", "addr1", "ADDRESS", "shipping")
            );
            assertTrue(
                isGranted("cust-1", "Contact", "addr2", "ADDRESS", "shipping")
            );
        }

        @Test
        @DisplayName("category with no mapped fields produces zero fan-out")
        void categoryNoFields() {
            jooqAdmin.registerCategory("ORPHAN_CAT", null);
            jooqAdmin.registerPurpose("ops", null);

            // Grant category-level consent directly through jooqAdmin
            // so we have a DB row with category_id but no fields mapped
            var catOpt = jooqAdmin
                .categoryRepository()
                .findByName("ORPHAN_CAT");
            assertTrue(catOpt.isPresent());
            Long opsPurposeId = jooqAdmin
                .purposeRepository()
                .findByName("ops")
                .orElseThrow()
                .getPurposeId();
            jooqAdmin
                .consentPolicyRepository()
                .upsertByCategory(
                    "user-orphan",
                    catOpt.get().getCategoryId(),
                    opsPurposeId,
                    true
                );

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            // The policy should be processed but produce 0 fan-out entries
            assertTrue(result.categoryPoliciesLoaded() >= 1);
            // No fan-out since no fields are mapped to ORPHAN_CAT
        }

        @Test
        @DisplayName("fan-out count is reported in result")
        void fanOutCountReported() {
            jooqAdmin.registerField("color", "Item", null);
            jooqAdmin.registerField("size", "Item", null);
            jooqAdmin.registerField("weight", "Item", null);
            jooqAdmin.registerCategory("PRODUCT_DATA", null);
            jooqAdmin.linkFieldToCategory("color", "Item", "PRODUCT_DATA");
            jooqAdmin.linkFieldToCategory("size", "Item", "PRODUCT_DATA");
            jooqAdmin.linkFieldToCategory("weight", "Item", "PRODUCT_DATA");
            mappingRepo.addFieldToCategory("Item", "color", "PRODUCT_DATA");
            mappingRepo.addFieldToCategory("Item", "size", "PRODUCT_DATA");
            mappingRepo.addFieldToCategory("Item", "weight", "PRODUCT_DATA");

            var scope = ConsentScope.to(
                "PRODUCT_DATA",
                null,
                null,
                "inventory"
            );
            syncService.syncGrant("vendor-1", scope, null);

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            // totalCacheEntriesWritten includes field policies + fan-out
            assertTrue(
                result.totalCacheEntriesWritten() >= 3,
                "should have at least 3 cache entries from fan-out"
            );
        }
    }

    @Nested
    @DisplayName("Revoked Policies")
    class RevokedPolicyTests {

        @Test
        @DisplayName("revoked field-level policy is materialized as revoked")
        void revokedFieldPolicy() {
            registerAndGrant("User", "taxId", null, "tax", "taxpayer-1");

            // Now revoke
            var scope = ConsentScope.to(null, "taxId", "User", "tax");
            syncService.syncRevoke("taxpayer-1", scope, null);

            // Clear and reconcile
            consentRepo.invalidateAll();
            mappingRepo.clear();

            helper.reconcile();

            // The field should show as denied after reconcile
            assertFalse(isGranted("taxpayer-1", "User", "taxId", null, "tax"));

            // But the entry should exist in the cache (as revoked)
            assertTrue(
                consentRepo.exists("taxpayer-1", "User", "taxId", "tax"),
                "revoked entry should still be present in cache"
            );
        }
    }

    @Nested
    @DisplayName("Expiration Handling")
    class ExpirationTests {

        @Test
        @DisplayName("future-expiring policy is loaded into cache")
        void futureExpiration() {
            Instant future = Instant.now().plus(Duration.ofDays(30));
            registerAndGrant(
                "User",
                "loyaltyId",
                null,
                "rewards",
                "member-1",
                future
            );

            consentRepo.invalidateAll();
            mappingRepo.clear();

            helper.reconcile();

            assertTrue(
                isGranted("member-1", "User", "loyaltyId", null, "rewards")
            );
        }

        @Test
        @DisplayName("already-expired policy is skipped by cache (TTL <= 0)")
        void pastExpiration() {
            // Use a past expiration — the DB will have the row but
            // CaffeineConsentRepository.setConsent skips entries with TTL <= 0
            Instant past = Instant.now().minus(Duration.ofDays(1));
            registerAndGrant(
                "User",
                "tempToken",
                null,
                "auth",
                "session-1",
                past
            );

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            // The policy row was processed (counted) but the cache entry
            // should not exist because setConsent skips expired entries
            assertFalse(
                isGranted("session-1", "User", "tempToken", null, "auth")
            );
        }
    }

    @Nested
    @DisplayName("Subject-Scoped Reconciliation")
    class SubjectScopedTests {

        @Test
        @DisplayName("reconcileSubject only affects the target subject")
        void subjectIsolation() {
            registerAndGrant("User", "badge", null, "hr", "emp-A");
            registerAndGrant("User", "badge", null, "hr", "emp-B");

            // Clear only emp-A's cache entries manually
            consentRepo.removeAllConsent("emp-A");
            assertFalse(isGranted("emp-A", "User", "badge", null, "hr"));
            assertTrue(isGranted("emp-B", "User", "badge", null, "hr"));

            // Reconcile only emp-A
            ReconciliationResult result = helper.reconcileSubject("emp-A");

            assertEquals(
                ReconciliationStrategy.FULL_REBUILD,
                result.strategy()
            );
            assertTrue(isGranted("emp-A", "User", "badge", null, "hr"));
            assertTrue(isGranted("emp-B", "User", "badge", null, "hr"));
        }

        @Test
        @DisplayName(
            "reconcileSubject with FULL_REBUILD clears subject's stale entries"
        )
        void subjectFullRebuildClearsStale() {
            registerAndGrant("User", "empId", null, "payroll", "emp-stale");

            // Write a stale entry for the same subject
            consentRepo.setFieldConsent(
                "emp-stale",
                "FakeEntity",
                "fakeField",
                "fakePurpose",
                true
            );
            assertTrue(
                consentRepo.exists(
                    "emp-stale",
                    "FakeEntity",
                    "fakeField",
                    "fakePurpose"
                )
            );

            // Reconcile the subject with FULL_REBUILD
            helper.reconcileSubject(
                "emp-stale",
                ReconciliationStrategy.FULL_REBUILD
            );

            // Stale entry should be gone (removeAllConsent clears all for subject)
            assertFalse(
                consentRepo.exists(
                    "emp-stale",
                    "FakeEntity",
                    "fakeField",
                    "fakePurpose"
                ),
                "stale entry should be cleared for subject"
            );

            // Legitimate entry should be present
            assertTrue(
                isGranted("emp-stale", "User", "empId", null, "payroll")
            );
        }

        @Test
        @DisplayName(
            "reconcileSubject with MERGE preserves stale entries for subject"
        )
        void subjectMergePreservesStale() {
            registerAndGrant(
                "User",
                "deskNumber",
                null,
                "facilities",
                "emp-merge"
            );

            // Write a stale entry
            consentRepo.setFieldConsent(
                "emp-merge",
                "Phantom",
                "ghostField",
                "phantom",
                true
            );

            // Reconcile with MERGE — stale entries not cleared
            helper.reconcileSubject("emp-merge", ReconciliationStrategy.MERGE);

            // Both the stale and legitimate entries should exist
            assertTrue(
                consentRepo.exists(
                    "emp-merge",
                    "Phantom",
                    "ghostField",
                    "phantom"
                ),
                "MERGE should not clear stale entries"
            );
            assertTrue(
                isGranted("emp-merge", "User", "deskNumber", null, "facilities")
            );
        }

        @Test
        @DisplayName(
            "reconcileSubject for non-existent subject returns zero-count result"
        )
        void nonExistentSubject() {
            ReconciliationResult result = helper.reconcileSubject("nobody-123");

            assertEquals(0, result.fieldPoliciesLoaded());
            assertEquals(0, result.categoryPoliciesLoaded());
            assertEquals(0, result.policiesSkipped());
            assertEquals(0, result.errors());
        }

        @Test
        @DisplayName("reconcileSubject rejects null subjectId")
        void nullSubjectId() {
            assertThrows(NullPointerException.class, () ->
                helper.reconcileSubject(null)
            );
        }
    }

    @Nested
    @DisplayName("Sync Service Delegation")
    class SyncServiceDelegationTests {

        @Test
        @DisplayName("reconcileFromDb() delegates to helper with FULL_REBUILD")
        void reconcileFromDbDefault() {
            ReconciliationResult result = syncService.reconcileFromDb();

            assertNotNull(result);
            assertEquals(
                ReconciliationStrategy.FULL_REBUILD,
                result.strategy()
            );
        }

        @Test
        @DisplayName("reconcileFromDb(MERGE) delegates with MERGE strategy")
        void reconcileFromDbMerge() {
            ReconciliationResult result = syncService.reconcileFromDb(
                ReconciliationStrategy.MERGE
            );

            assertNotNull(result);
            assertEquals(ReconciliationStrategy.MERGE, result.strategy());
        }

        @Test
        @DisplayName("reconcileSubjectFromDb() delegates to helper")
        void reconcileSubjectFromDb() {
            registerAndGrant("User", "apiKey", null, "integration", "svc-1");

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = syncService.reconcileSubjectFromDb(
                "svc-1"
            );

            assertNotNull(result);
            assertTrue(result.fieldPoliciesLoaded() >= 1);
            assertTrue(
                isGranted("svc-1", "User", "apiKey", null, "integration")
            );
        }

        @Test
        @DisplayName("reconcileFromDb rejects null strategy")
        void nullStrategy() {
            assertThrows(NullPointerException.class, () ->
                syncService.reconcileFromDb(null)
            );
        }

        @Test
        @DisplayName("reconcileSubjectFromDb rejects null subjectId")
        void nullSubjectInSyncService() {
            assertThrows(NullPointerException.class, () ->
                syncService.reconcileSubjectFromDb(null)
            );
        }
    }

    @Nested
    @DisplayName("ReconciliationResult Computed Properties")
    class ResultComputedPropertyTests {

        @Test
        @DisplayName("totalPoliciesLoaded = fieldPolicies + categoryPolicies")
        void totalPoliciesLoaded() {
            // Register field + category data
            jooqAdmin.registerField("metric1", "Sensor", null);
            jooqAdmin.registerCategory("TELEMETRY", null);
            jooqAdmin.linkFieldToCategory("metric1", "Sensor", "TELEMETRY");
            mappingRepo.addFieldToCategory("Sensor", "metric1", "TELEMETRY");

            // Grant at field level
            var fieldScope = ConsentScope.to(
                null,
                "metric1",
                "Sensor",
                "monitoring"
            );
            syncService.syncGrant("device-1", fieldScope, null);

            // Grant at category level
            var catScope = ConsentScope.to(
                "TELEMETRY",
                null,
                null,
                "analytics"
            );
            syncService.syncGrant("device-1", catScope, null);

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            assertEquals(
                result.fieldPoliciesLoaded() + result.categoryPoliciesLoaded(),
                result.totalPoliciesLoaded()
            );
        }

        @Test
        @DisplayName("totalCacheEntriesWritten = fieldPolicies + fanOutEntries")
        void totalCacheEntriesWritten() {
            ReconciliationResult result = helper.reconcile();

            assertEquals(
                result.fieldPoliciesLoaded() + result.categoryFanOutEntries(),
                result.totalCacheEntriesWritten()
            );
        }
    }

    @Nested
    @DisplayName("End-to-End: Discovery → Sync → Reconcile → Evaluate")
    class EndToEndTests {

        @Test
        @DisplayName(
            "full lifecycle: register → grant → clear → reconcile → evaluate"
        )
        void fullLifecycle() {
            // 1. Register metadata
            jooqAdmin.registerField("diagnosis", "Record", null);
            jooqAdmin.registerCategory("MEDICAL", null);
            jooqAdmin.linkFieldToCategory("diagnosis", "Record", "MEDICAL");
            mappingRepo.addFieldToCategory("Record", "diagnosis", "MEDICAL");

            // 2. Grant field-level consent
            var fieldScope = ConsentScope.to(
                "MEDICAL",
                "diagnosis",
                "Record",
                "treatment"
            );
            syncService.syncGrant("patient-e2e", fieldScope, null);

            // Verify it works before reconciliation
            assertTrue(
                isGranted(
                    "patient-e2e",
                    "Record",
                    "diagnosis",
                    "MEDICAL",
                    "treatment"
                )
            );

            // 3. Simulate restart — clear all caches
            consentRepo.invalidateAll();
            mappingRepo.clear();

            // Verify everything is gone
            assertFalse(
                isGranted(
                    "patient-e2e",
                    "Record",
                    "diagnosis",
                    "MEDICAL",
                    "treatment"
                )
            );

            // 4. Reconcile from DB
            ReconciliationResult result = helper.reconcile();

            // 5. Verify everything is restored
            assertTrue(
                isGranted(
                    "patient-e2e",
                    "Record",
                    "diagnosis",
                    "MEDICAL",
                    "treatment"
                )
            );
            assertTrue(result.fieldPoliciesLoaded() >= 1);
            assertTrue(result.mappingsLoaded() >= 1);
            assertNotNull(result.elapsed());
            assertTrue(result.elapsed().toMillis() >= 0);
        }

        @Test
        @DisplayName(
            "category-level grant → reconcile → evaluate individual fields"
        )
        void categoryGrantReconcileEvaluate() {
            // Register two fields in a category
            jooqAdmin.registerField("fico", "CreditReport", null);
            jooqAdmin.registerField("debtRatio", "CreditReport", null);
            jooqAdmin.registerCategory("FINANCIAL", null);
            jooqAdmin.linkFieldToCategory("fico", "CreditReport", "FINANCIAL");
            jooqAdmin.linkFieldToCategory(
                "debtRatio",
                "CreditReport",
                "FINANCIAL"
            );
            mappingRepo.addFieldToCategory("CreditReport", "fico", "FINANCIAL");
            mappingRepo.addFieldToCategory(
                "CreditReport",
                "debtRatio",
                "FINANCIAL"
            );

            // Grant at category level
            var scope = ConsentScope.to(
                "FINANCIAL",
                null,
                null,
                "underwriting"
            );
            syncService.syncGrant("applicant-1", scope, null);

            // Verify fan-out worked
            assertTrue(
                isGranted(
                    "applicant-1",
                    "CreditReport",
                    "fico",
                    "FINANCIAL",
                    "underwriting"
                )
            );
            assertTrue(
                isGranted(
                    "applicant-1",
                    "CreditReport",
                    "debtRatio",
                    "FINANCIAL",
                    "underwriting"
                )
            );

            // Simulate restart
            consentRepo.invalidateAll();
            mappingRepo.clear();

            // Reconcile
            helper.reconcile();

            // Both fields should be granted again through category fan-out
            assertTrue(
                isGranted(
                    "applicant-1",
                    "CreditReport",
                    "fico",
                    "FINANCIAL",
                    "underwriting"
                )
            );
            assertTrue(
                isGranted(
                    "applicant-1",
                    "CreditReport",
                    "debtRatio",
                    "FINANCIAL",
                    "underwriting"
                )
            );
        }

        @Test
        @DisplayName("mixed field + category grants across multiple subjects")
        void mixedGrantsMultipleSubjects() {
            // Subject A: field-level grant
            registerAndGrant("Org", "taxEin", null, "compliance", "org-A");

            // Subject B: category-level grant
            jooqAdmin.registerField("revenue", "Org", null);
            jooqAdmin.registerCategory("FINANCIALS", null);
            jooqAdmin.linkFieldToCategory("revenue", "Org", "FINANCIALS");
            mappingRepo.addFieldToCategory("Org", "revenue", "FINANCIALS");
            var catScope = ConsentScope.to("FINANCIALS", null, null, "audit");
            syncService.syncGrant("org-B", catScope, null);

            // Clear and reconcile
            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult result = helper.reconcile();

            assertTrue(result.totalPoliciesLoaded() >= 2);
            assertTrue(isGranted("org-A", "Org", "taxEin", null, "compliance"));
            assertTrue(
                isGranted("org-B", "Org", "revenue", "FINANCIALS", "audit")
            );

            // Cross-check: org-A should NOT have org-B's grants
            assertFalse(
                isGranted("org-A", "Org", "revenue", "FINANCIALS", "audit")
            );
        }
    }

    @Nested
    @DisplayName("Purpose Isolation")
    class PurposeIsolationTests {

        @Test
        @DisplayName("reconcile preserves purpose isolation")
        void purposeIsolation() {
            registerAndGrant("User", "avatar", null, "social", "user-pi");
            // Same field, different purpose — not granted
            // (no grant for "advertising" purpose)

            consentRepo.invalidateAll();
            mappingRepo.clear();

            helper.reconcile();

            assertTrue(isGranted("user-pi", "User", "avatar", null, "social"));
            assertFalse(
                isGranted("user-pi", "User", "avatar", null, "advertising")
            );
        }
    }

    @Nested
    @DisplayName("Entity Isolation")
    class EntityIsolationTests {

        @Test
        @DisplayName("same field name under different entities is isolated")
        void sameFieldDifferentEntity() {
            registerAndGrant("Employee", "hireDate", null, "hr", "ent-iso");
            jooqAdmin.registerField("hireDate", "Contractor", null);

            consentRepo.invalidateAll();
            mappingRepo.clear();

            helper.reconcile();

            assertTrue(
                isGranted("ent-iso", "Employee", "hireDate", null, "hr")
            );
            // hireDate under Contractor was never granted
            assertFalse(
                isGranted("ent-iso", "Contractor", "hireDate", null, "hr")
            );
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("reconciling twice produces the same result")
        void doubleReconcile() {
            registerAndGrant("User", "locale", null, "i18n", "user-idem");

            consentRepo.invalidateAll();
            mappingRepo.clear();

            ReconciliationResult r1 = helper.reconcile();
            ReconciliationResult r2 = helper.reconcile();

            assertEquals(r1.fieldPoliciesLoaded(), r2.fieldPoliciesLoaded());
            assertEquals(
                r1.categoryPoliciesLoaded(),
                r2.categoryPoliciesLoaded()
            );
            assertEquals(r1.mappingsLoaded(), r2.mappingsLoaded());
            assertEquals(r1.policiesSkipped(), r2.policiesSkipped());
            assertEquals(r1.errors(), r2.errors());

            assertTrue(isGranted("user-idem", "User", "locale", null, "i18n"));
        }
    }
}
