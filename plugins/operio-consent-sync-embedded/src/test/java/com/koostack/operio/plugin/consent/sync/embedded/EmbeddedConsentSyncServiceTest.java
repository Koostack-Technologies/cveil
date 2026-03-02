package com.koostack.operio.plugin.consent.sync.embedded;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentRepository;
import com.koostack.operio.plugin.consent.caffeine.CaffeineFieldCategoryMappingRepository;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminCommandService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminQueryService;
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedConsentSyncServiceTest {

    private EmbeddedConsentSyncServiceImpl syncService;
    private JooqConsentAdminQueryService queryService;
    private CaffeineConsentDecisionService caffeineDecision;
    private CaffeineConsentRepository consentRepo;
    private CaffeineFieldCategoryMappingRepository mappingRepo;

    @BeforeAll
    void setUpService() {
        var config = new EmbeddedConsentSyncServiceConfig(
            new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:embedded_sync_test;DB_CLOSE_DELAY=-1",
                "H2"
            ),
            new CaffeineConsentDecisionServiceConfig()
        );

        syncService = new EmbeddedConsentSyncServiceImpl();
        syncService.configure(config);

        queryService = syncService.queryService();
        caffeineDecision = syncService.caffeineDecisionService();
        consentRepo = syncService.caffeineConsentRepository();
        mappingRepo = syncService.caffeineMappingRepository();
    }

    @BeforeEach
    void clearCaches() {
        consentRepo.invalidateAll();
        mappingRepo.clear();
    }

    // =======================================================================
    // Service lifecycle
    // =======================================================================

    @Nested
    @DisplayName("Service Lifecycle")
    class ServiceLifecycle {

        @Test
        @DisplayName("name is 'embedded-sync'")
        void nameIsEmbeddedSync() {
            assertEquals("embedded-sync", syncService.getName());
        }

        @Test
        @DisplayName("priority is 300")
        void priorityIs300() {
            assertEquals(300, syncService.getPriority());
        }

        @Test
        @DisplayName("configuration type is EmbeddedConsentSyncServiceConfig")
        void configurationType() {
            assertEquals(
                EmbeddedConsentSyncServiceConfig.class,
                syncService.getConfigurationType()
            );
        }

        @Test
        @DisplayName(
            "unconfigured service throws OperioConfigurationException on syncGrant"
        )
        void unconfiguredServiceThrowsOnGrant() {
            var unconfigured = new EmbeddedConsentSyncServiceImpl();
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            assertThrows(OperioConfigurationException.class, () ->
                unconfigured.syncGrant("user-1", scope, null)
            );
        }

        @Test
        @DisplayName(
            "unconfigured service throws OperioConfigurationException on syncRevoke"
        )
        void unconfiguredServiceThrowsOnRevoke() {
            var unconfigured = new EmbeddedConsentSyncServiceImpl();
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            assertThrows(OperioConfigurationException.class, () ->
                unconfigured.syncRevoke("user-1", scope, null)
            );
        }

        @Test
        @DisplayName("isReady returns true after configure")
        void isReadyAfterConfigure() {
            assertTrue(syncService.isReady());
        }

        @Test
        @DisplayName("isReady returns false before configure")
        void isReadyBeforeConfigure() {
            var unconfigured = new EmbeddedConsentSyncServiceImpl();
            assertFalse(unconfigured.isReady());
        }
    }

    // =======================================================================
    // Instance sharing
    // =======================================================================

    @Nested
    @DisplayName("Instance Sharing")
    class InstanceSharing {

        @Test
        @DisplayName(
            "queryService() returns a non-null configured instance"
        )
        void queryServiceAccessible() {
            assertNotNull(queryService);
            assertTrue(queryService.isReady());
        }

        @Test
        @DisplayName(
            "caffeineDecisionService() returns a non-null configured instance"
        )
        void caffeineDecisionAccessible() {
            assertNotNull(caffeineDecision);
            assertTrue(caffeineDecision.isReady());
        }

        @Test
        @DisplayName(
            "caffeineConsentRepository() is the same instance as decision service's repo"
        )
        void consentRepoIsShared() {
            assertSame(consentRepo, caffeineDecision.consentRepository());
        }

        @Test
        @DisplayName(
            "caffeineMappingRepository() is the same instance as decision service's repo"
        )
        void mappingRepoIsShared() {
            assertSame(
                mappingRepo,
                caffeineDecision.fieldCategoryMappingRepository()
            );
        }

        @Test
        @DisplayName(
            "sync writes are visible through the decision service's evaluateConsent"
        )
        void syncWritesVisibleThroughDecision() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().contains(scope));
        }
    }

    // =======================================================================
    // Dual-write — grant
    // =======================================================================

    @Nested
    @DisplayName("Dual-Write Grant")
    class DualWriteGrant {

        @Test
        @DisplayName("syncGrant writes to both DB and Caffeine cache")
        void grantWritesToBothStores() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, null);

            // Caffeine cache should have the entry
            assertTrue(
                consentRepo.exists("user-1", "User", "email", "marketing")
            );
            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("GRANTED", entry.get().status());
            assertEquals("FIELD", entry.get().source());

            // Decision service should evaluate as granted
            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("syncGrant with expiration sets TTL on cache entry")
        void grantWithExpiration() {
            Instant future = Instant.now().plus(Duration.ofHours(1));
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, future);

            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("GRANTED", entry.get().status());
            assertEquals(future, entry.get().expiresAt());
        }

        @Test
        @DisplayName("syncGrant with null expiration means no TTL")
        void grantWithNullExpiration() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, null);

            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertNull(entry.get().expiresAt());
        }

        @Test
        @DisplayName("multiple grants for different fields")
        void multipleGrants() {
            var emailScope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            var phoneScope = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "marketing"
            );

            syncService.syncGrant("user-1", emailScope, null);
            syncService.syncGrant("user-1", phoneScope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(emailScope, phoneScope)
            );
            assertEquals(2, response.granted().size());
            assertTrue(response.granted().contains(emailScope));
            assertTrue(response.granted().contains(phoneScope));
        }

        @Test
        @DisplayName("grants are isolated per subject")
        void grantsIsolatedPerSubject() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, null);

            ConsentResponse r1 = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = caffeineDecision.evaluateConsent(
                "user-2",
                Set.of(scope)
            );

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
        }
    }

    // =======================================================================
    // Dual-write — revoke
    // =======================================================================

    @Nested
    @DisplayName("Dual-Write Revoke")
    class DualWriteRevoke {

        @Test
        @DisplayName("syncRevoke writes a REVOKED entry to both stores")
        void revokeWritesToBothStores() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            // First grant, then revoke
            syncService.syncGrant("user-1", scope, null);
            syncService.syncRevoke("user-1", scope, null);

            // Caffeine cache should have the revoked entry
            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("REVOKED", entry.get().status());

            // Decision service should evaluate as denied
            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("syncRevoke with expiration sets TTL on cache entry")
        void revokeWithExpiration() {
            Instant future = Instant.now().plus(Duration.ofHours(1));
            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            syncService.syncGrant("user-1", scope, null);
            syncService.syncRevoke("user-1", scope, future);

            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("REVOKED", entry.get().status());
            assertEquals(future, entry.get().expiresAt());
        }

        @Test
        @DisplayName("revoke without prior grant still writes REVOKED")
        void revokeWithoutPriorGrant() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncRevoke("user-1", scope, null);

            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("REVOKED", entry.get().status());
        }
    }

    // =======================================================================
    // Category fan-out
    // =======================================================================

    @Nested
    @DisplayName("Category Fan-Out")
    class CategoryFanOut {

        @Test
        @DisplayName("category-level grant fans out to all fields in category")
        void categoryGrantFanOut() {
            // Register field-category mappings in the cache
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");

            // Grant at category level (fieldName=null, category=PII)
            var scope = new ConsentScope("PII", null, null, "marketing");
            syncService.syncGrant("user-1", scope, null);

            // Both fields should be granted in the cache
            var emailScope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            var phoneScope = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "marketing"
            );

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(emailScope, phoneScope)
            );

            assertEquals(2, response.granted().size());
            assertTrue(response.granted().contains(emailScope));
            assertTrue(response.granted().contains(phoneScope));
        }

        @Test
        @DisplayName("category fan-out does not overwrite field-level entries")
        void categoryFanOutRespectsFieldPrecedence() {
            // Field-level revoke for email
            var emailScope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            syncService.syncRevoke("user-1", emailScope, null);

            // Register category mapping
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");

            // Category-level grant — should NOT overwrite email's field-level revoke
            var categoryScope = new ConsentScope(
                "PII",
                null,
                null,
                "marketing"
            );
            syncService.syncGrant("user-1", categoryScope, null);

            var phoneScope = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "marketing"
            );

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(emailScope, phoneScope)
            );

            // email: field-level revoke survives
            assertTrue(response.denied().contains(emailScope));
            // phone: category-level grant applied
            assertTrue(response.granted().contains(phoneScope));
        }

        @Test
        @DisplayName("category fan-out with expiration")
        void categoryFanOutWithExpiration() {
            mappingRepo.addFieldToCategory("User", "email", "PII");

            Instant future = Instant.now().plus(Duration.ofHours(1));
            var categoryScope = new ConsentScope(
                "PII",
                null,
                null,
                "marketing"
            );
            syncService.syncGrant("user-1", categoryScope, future);

            var entry = consentRepo.getConsentEntry(
                "user-1",
                "User",
                "email",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals("GRANTED", entry.get().status());
            assertEquals("CATEGORY", entry.get().source());
            assertEquals(future, entry.get().expiresAt());
        }

        @Test
        @DisplayName("category fan-out with empty mapping is a no-op on cache")
        void categoryFanOutEmptyMapping() {
            // No mappings registered for "HEALTH"
            var scope = new ConsentScope("HEALTH", null, null, "research");
            syncService.syncGrant("user-1", scope, null);

            // Cache should have no entries
            consentRepo.cleanUp();
            assertEquals(0, consentRepo.estimatedSize());
        }
    }

    // =======================================================================
    // Entity isolation
    // =======================================================================

    @Nested
    @DisplayName("Entity Isolation")
    class EntityIsolation {

        @Test
        @DisplayName("same field name on different entities is isolated")
        void sameFieldDifferentEntity() {
            var userEmailScope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            var contactEmailScope = ConsentScope.to(
                "PII",
                "email",
                "Contact",
                "marketing"
            );

            syncService.syncGrant("user-1", userEmailScope, null);
            syncService.syncRevoke("user-1", contactEmailScope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(userEmailScope, contactEmailScope)
            );

            assertTrue(response.granted().contains(userEmailScope));
            assertTrue(response.denied().contains(contactEmailScope));
        }

        @Test
        @DisplayName("null entity uses _default_ sentinel")
        void nullEntitySentinel() {
            var scope = ConsentScope.to("PII", "zipcode", "marketing");
            syncService.syncGrant("user-1", scope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.granted().contains(scope));
        }
    }

    // =======================================================================
    // Purpose isolation
    // =======================================================================

    @Nested
    @DisplayName("Purpose Isolation")
    class PurposeIsolation {

        @Test
        @DisplayName("grants are isolated per purpose")
        void purposeIsolation() {
            var marketing = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            var analytics = ConsentScope.to(
                "PII",
                "email",
                "User",
                "analytics"
            );

            syncService.syncGrant("user-1", marketing, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertTrue(response.granted().contains(marketing));
            assertTrue(response.denied().contains(analytics));
        }
    }

    // =======================================================================
    // AppNameAware
    // =======================================================================

    @Nested
    @DisplayName("AppNameAware")
    class AppNameAwareTests {

        @Test
        @DisplayName("setAppName propagates to Caffeine decision service")
        void setAppNamePropagates() {
            syncService.setAppName("my-app");
            assertEquals("my-app", caffeineDecision.getAppName());
            // reset
            syncService.setAppName("operio");
        }
    }

    // =======================================================================
    // Grant/revoke toggling
    // =======================================================================

    @Nested
    @DisplayName("Grant/Revoke Toggling")
    class GrantRevokeToggling {

        @Test
        @DisplayName("grant then revoke then re-grant")
        void toggleGrantRevokeGrant() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            // Grant
            syncService.syncGrant("user-1", scope, null);
            ConsentResponse r1 = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(r1.granted().contains(scope));

            // Revoke
            syncService.syncRevoke("user-1", scope, null);
            ConsentResponse r2 = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(r2.denied().contains(scope));

            // Re-grant
            syncService.syncGrant("user-1", scope, null);
            ConsentResponse r3 = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(r3.granted().contains(scope));
        }

        @Test
        @DisplayName("revoke then grant overrides revocation")
        void revokeOverriddenByGrant() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            syncService.syncRevoke("user-1", scope, null);
            syncService.syncGrant("user-1", scope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.granted().contains(scope));
        }
    }

    // =======================================================================
    // Edge cases
    // =======================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("grant with past expiration — cache entry is not written")
        void grantWithPastExpiration() {
            Instant past = Instant.now().minus(Duration.ofHours(1));
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, past);

            // The Caffeine cache should skip the write for past expiry
            assertFalse(
                consentRepo.exists("user-1", "User", "email", "marketing")
            );
        }

        @Test
        @DisplayName("multiple subjects with independent consent states")
        void multipleSubjectsIndependent() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            syncService.syncGrant("user-1", scope, null);
            syncService.syncRevoke("user-2", scope, null);
            // user-3 has no consent record

            ConsentResponse r1 = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = caffeineDecision.evaluateConsent(
                "user-2",
                Set.of(scope)
            );
            ConsentResponse r3 = caffeineDecision.evaluateConsent(
                "user-3",
                Set.of(scope)
            );

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
            assertTrue(r3.denied().contains(scope));
        }
    }

    // =======================================================================
    // Test isolation check
    // =======================================================================

    @Nested
    @DisplayName("Test Isolation Check")
    class TestIsolationCheck {

        @Test
        @DisplayName("isolation check 1 — cache is empty after clearCaches")
        void emptyRun1() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("isolation check 2 — writes don't leak to next test")
        void emptyRun2() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            syncService.syncGrant("user-1", scope, null);

            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("isolation check 3 — previous test's cache data is gone")
        void emptyRun3() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = caffeineDecision.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.denied().contains(scope));
        }
    }

    // =======================================================================
    // Auto-registration opt-out
    // =======================================================================

    @Nested
    @DisplayName("Auto-Registration Disabled")
    class AutoRegistrationDisabledTests {

        private EmbeddedConsentSyncServiceImpl strictService;

        @BeforeEach
        void setUpStrictService() {
            var config = new EmbeddedConsentSyncServiceConfig(
                new JooqConsentAdminServiceConfig(
                    "jdbc:h2:mem:strict_sync_test_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1",
                    "H2"
                ),
                new CaffeineConsentDecisionServiceConfig(),
                false // auto-registration disabled
            );
            strictService = new EmbeddedConsentSyncServiceImpl();
            strictService.configure(config);
        }

        @Test
        @DisplayName(
            "grant still writes to cache even when auto-registration is off (cache-only mode)"
        )
        void grantWritesToCacheWithoutAutoRegistration() {
            var scope = ConsentScope.to(
                "PII",
                "firstName",
                "Person",
                "analytics"
            );
            // In cache-only mode (no event service), syncGrant writes
            // directly to cache without metadata validation
            assertDoesNotThrow(
                () -> strictService.syncGrant("user-1", scope, null)
            );
        }

        @Test
        @DisplayName(
            "revoke still writes to cache even when auto-registration is off (cache-only mode)"
        )
        void revokeWritesToCacheWithoutAutoRegistration() {
            var scope = ConsentScope.to(
                "PII",
                "firstName",
                "Person",
                "analytics"
            );
            // In cache-only mode (no event service), syncRevoke writes
            // directly to cache without metadata validation
            assertDoesNotThrow(
                () -> strictService.syncRevoke("user-1", scope, null)
            );
        }

        @Test
        @DisplayName("grant succeeds when metadata is pre-registered manually")
        void grantSucceedsWithPreRegisteredMetadata() {
            // In cache-only mode (no event service), we pre-populate the
            // Caffeine mapping repo directly so cache fan-out works
            strictService
                .caffeineMappingRepository()
                .addFieldToCategory("Person", "firstName", "PII");

            var scope = ConsentScope.to(
                "PII",
                "firstName",
                "Person",
                "analytics"
            );

            // syncGrant writes directly to cache in cache-only mode
            assertDoesNotThrow(() ->
                strictService.syncGrant("user-1", scope, null)
            );

            // Verify the grant went through to the cache
            var response = strictService
                .caffeineDecisionService()
                .evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("default config has auto-registration enabled")
        void defaultConfigHasAutoRegistrationEnabled() {
            var defaultConfig = new EmbeddedConsentSyncServiceConfig(
                new JooqConsentAdminServiceConfig(
                    "jdbc:h2:mem:default_flag_test;DB_CLOSE_DELAY=-1",
                    "H2"
                ),
                new CaffeineConsentDecisionServiceConfig()
            );
            assertTrue(
                defaultConfig.autoRegisterMetadata(),
                "2-arg constructor should default to true"
            );
        }

        @Test
        @DisplayName("3-arg config with false disables auto-registration")
        void threeArgConfigDisablesAutoRegistration() {
            var config = new EmbeddedConsentSyncServiceConfig(
                new JooqConsentAdminServiceConfig(
                    "jdbc:h2:mem:flag_off_test;DB_CLOSE_DELAY=-1",
                    "H2"
                ),
                new CaffeineConsentDecisionServiceConfig(),
                false
            );
            assertFalse(config.autoRegisterMetadata());
        }
    }
}
