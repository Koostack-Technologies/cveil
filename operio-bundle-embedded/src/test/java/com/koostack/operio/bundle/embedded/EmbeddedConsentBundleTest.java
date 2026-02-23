package com.koostack.operio.bundle.embedded;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.Operio;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.sync.embedded.ReconciliationStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedConsentBundleTest {

    // =======================================================================
    // Default-config bundle (zero-config constructor)
    // =======================================================================

    @Nested
    @DisplayName("Default Configuration")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DefaultConfig {

        private Operio operio;

        @BeforeAll
        void buildOperio() {
            operio = Operio.builder()
                .withBundle(new EmbeddedConsentBundle())
                .build();
        }

        @Test
        @DisplayName("bundle name is 'embedded'")
        void bundleName() {
            assertEquals("embedded", new EmbeddedConsentBundle().name());
        }

        @Test
        @DisplayName("Operio builds successfully with default embedded bundle")
        void buildSucceeds() {
            assertNotNull(operio);
        }

        @Test
        @DisplayName("consent service is wired")
        void consentServiceWired() {
            assertNotNull(operio.consentService());
        }

        @Test
        @DisplayName("consent admin service is wired")
        void consentAdminServiceWired() {
            assertNotNull(operio.consentAdminService());
        }

        @Test
        @DisplayName("consent sync service is wired")
        void consentSyncServiceWired() {
            assertNotNull(operio.consentSyncService());
        }

        @Test
        @DisplayName("hasConsentWriteProvider returns true")
        void hasWriteProvider() {
            assertTrue(operio.hasConsentWriteProvider());
        }

        @Test
        @DisplayName("consent service is a CaffeineConsentDecisionService")
        void consentServiceIsCaffeine() {
            assertInstanceOf(
                CaffeineConsentDecisionService.class,
                operio.consentService()
            );
        }

        @Test
        @DisplayName("default appName is 'operio'")
        void defaultAppName() {
            assertEquals("operio", operio.appName());
        }
    }

    // =======================================================================
    // Custom-config bundle
    // =======================================================================

    @Nested
    @DisplayName("Custom Configuration")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CustomConfig {

        private Operio operio;

        @BeforeAll
        void buildOperio() {
            var jooqConfig = new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:embedded_bundle_custom;DB_CLOSE_DELAY=-1",
                "H2"
            );
            var caffeineConfig = new CaffeineConsentDecisionServiceConfig(
                50_000
            );

            operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        "my-test-app",
                        jooqConfig,
                        caffeineConfig,
                        null
                    )
                )
                .build();
        }

        @Test
        @DisplayName("appName is set to custom value")
        void customAppName() {
            assertEquals("my-test-app", operio.appName());
        }

        @Test
        @DisplayName("Operio builds successfully with custom configs")
        void buildSucceeds() {
            assertNotNull(operio);
            assertNotNull(operio.consentService());
            assertNotNull(operio.consentAdminService());
            assertNotNull(operio.consentSyncService());
        }
    }

    // =======================================================================
    // Instance sharing — the critical invariant for in-memory bundles
    // =======================================================================

    @Nested
    @DisplayName("Instance Sharing")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class InstanceSharing {

        private Operio operio;

        @BeforeAll
        void buildOperio() {
            operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:embedded_bundle_sharing;DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig()
                    )
                )
                .build();
        }

        @Test
        @DisplayName("grant through sync is visible through decision service")
        void grantVisibleThroughDecision() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            operio.grantConsent("sharing-user-1", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("sharing-user-1", Set.of(scope));

            assertTrue(
                response.granted().contains(scope),
                "Grant via sync should be readable via the decision service"
            );
        }

        @Test
        @DisplayName("revoke through sync is visible through decision service")
        void revokeVisibleThroughDecision() {
            var scope = ConsentScope.to("PII", "phone", "User", "marketing");

            operio.grantConsent("sharing-user-2", scope);
            operio.revokeConsent("sharing-user-2", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("sharing-user-2", Set.of(scope));

            assertTrue(
                response.denied().contains(scope),
                "Revoke via sync should be readable via the decision service"
            );
        }
    }

    // =======================================================================
    // End-to-end consent flow
    // =======================================================================

    @Nested
    @DisplayName("End-to-End Consent Flow")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class EndToEndFlow {

        private Operio operio;

        @BeforeAll
        void buildOperio() {
            operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:embedded_bundle_e2e;DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig()
                    )
                )
                .build();
        }

        @Test
        @DisplayName("grant → evaluate → granted")
        void grantThenEvaluate() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            operio.grantConsent("e2e-user-1", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-1", Set.of(scope));

            assertTrue(response.granted().contains(scope));
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName("no consent → evaluate → denied with reason")
        void noConsentThenEvaluate() {
            var scope = ConsentScope.to("PII", "ssn", "User", "marketing");

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-2", Set.of(scope));

            assertTrue(response.denied().contains(scope));
            assertEquals(
                "No consent record found",
                response.reasons().get("ssn")
            );
        }

        @Test
        @DisplayName("grant → revoke → evaluate → denied")
        void grantRevokeThenEvaluate() {
            var scope = ConsentScope.to("PII", "email", "User", "analytics");

            operio.grantConsent("e2e-user-3", scope);
            operio.revokeConsent("e2e-user-3", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-3", Set.of(scope));

            assertTrue(response.denied().contains(scope));
            assertEquals(
                "Consent explicitly revoked",
                response.reasons().get("email")
            );
        }

        @Test
        @DisplayName("grant with expiration includes expiresAt in detail")
        void grantWithExpiration() {
            Instant future = Instant.now().plus(Duration.ofHours(1));
            var scope = ConsentScope.to("PII", "email", "User", "retention");

            operio.grantConsent("e2e-user-4", scope, future);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-4", Set.of(scope));

            assertTrue(response.granted().contains(scope));

            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail);
            assertEquals("GRANTED", detail.effectiveStatus());
            assertNotNull(detail.expiresAt());
            assertEquals(future, detail.expiresAt());
        }

        @Test
        @DisplayName("multiple fields — mixed grant/revoke/absent")
        void multipleFieldsMixed() {
            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");
            var ssn = ConsentScope.to("PII", "ssn", "User", "marketing");

            operio.grantConsent("e2e-user-5", email);
            operio.revokeConsent("e2e-user-5", phone);
            // ssn has no consent record

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-5", Set.of(email, phone, ssn));

            assertEquals(1, response.granted().size());
            assertTrue(response.granted().contains(email));

            assertEquals(2, response.denied().size());
            assertTrue(response.denied().contains(phone));
            assertTrue(response.denied().contains(ssn));
        }

        @Test
        @DisplayName("grants are isolated per subject")
        void subjectIsolation() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            operio.grantConsent("e2e-user-6", scope);

            ConsentResponse r1 = operio
                .consentService()
                .evaluateConsent("e2e-user-6", Set.of(scope));
            ConsentResponse r2 = operio
                .consentService()
                .evaluateConsent("e2e-user-7", Set.of(scope));

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
        }

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

            operio.grantConsent("e2e-user-8", marketing);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-8", Set.of(marketing, analytics));

            assertTrue(response.granted().contains(marketing));
            assertTrue(response.denied().contains(analytics));
        }

        @Test
        @DisplayName("grants are isolated per entity")
        void entityIsolation() {
            var userEmail = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );
            var contactEmail = ConsentScope.to(
                "PII",
                "email",
                "Contact",
                "marketing"
            );

            operio.grantConsent("e2e-user-9", userEmail);
            operio.revokeConsent("e2e-user-9", contactEmail);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-9", Set.of(userEmail, contactEmail));

            assertTrue(response.granted().contains(userEmail));
            assertTrue(response.denied().contains(contactEmail));
        }

        @Test
        @DisplayName("null entity scope works (backward compat)")
        void nullEntityScope() {
            var scope = ConsentScope.to("PII", "zipcode", "marketing");
            operio.grantConsent("e2e-user-10", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-10", Set.of(scope));

            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("grant → revoke → re-grant → granted")
        void toggleGrantRevokeGrant() {
            var scope = ConsentScope.to("PII", "email", "User", "support");

            operio.grantConsent("e2e-user-11", scope);
            operio.revokeConsent("e2e-user-11", scope);
            operio.grantConsent("e2e-user-11", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-11", Set.of(scope));

            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("empty request set returns empty response")
        void emptyRequestSet() {
            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-12", Set.of());

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());
            assertTrue(response.details().isEmpty());
        }

        @Test
        @DisplayName("null request set returns empty response")
        void nullRequestSet() {
            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-13", null);

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName("details map is populated for all evaluated scopes")
        void detailsMapPopulated() {
            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            operio.grantConsent("e2e-user-14", email);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-user-14", Set.of(email, phone));

            assertEquals(2, response.details().size());

            DecisionDetail emailDetail = response.details().get(email);
            assertNotNull(emailDetail);
            assertEquals("GRANTED", emailDetail.effectiveStatus());
            assertEquals("FIELD", emailDetail.resolvedBy());

            DecisionDetail phoneDetail = response.details().get(phone);
            assertNotNull(phoneDetail);
            assertNull(phoneDetail.effectiveStatus());
            assertEquals("NONE", phoneDetail.resolvedBy());
        }

        @Test
        @DisplayName("large batch — 100 scopes")
        void largeBatch() {
            Set<ConsentScope> scopes = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                String field = "batch_field_" + i;
                var scope = ConsentScope.to("CAT", field, "Entity", "purpose");
                if (i % 2 == 0) {
                    operio.grantConsent("e2e-batch-user", scope);
                }
                scopes.add(scope);
            }

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("e2e-batch-user", scopes);

            assertEquals(50, response.granted().size());
            assertEquals(50, response.denied().size());
            assertEquals(100, response.details().size());
        }
    }

    // =======================================================================
    // Constructor overloads
    // =======================================================================

    @Nested
    @DisplayName("Constructor Overloads")
    class ConstructorOverloads {

        @Test
        @DisplayName("zero-arg constructor builds successfully")
        void zeroArg() {
            var bundle = new EmbeddedConsentBundle();
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertNotNull(operio.consentService());
            assertNotNull(operio.consentAdminService());
            assertNotNull(operio.consentSyncService());
        }

        @Test
        @DisplayName("discovery-only constructor builds successfully")
        void discoveryOnly() {
            // null discovery config means no discovery wired
            var bundle = new EmbeddedConsentBundle(
                (com.koostack.operio.plugin.discovery.sensitive.annotation.AnnotationDiscoveryConfig) null
            );
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertNotNull(operio.consentService());
        }

        @Test
        @DisplayName("jooq + caffeine config constructor builds successfully")
        void jooqAndCaffeineConfig() {
            var jooqConfig = new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:ctor_test_1;DB_CLOSE_DELAY=-1",
                "H2"
            );
            var caffeineConfig = new CaffeineConsentDecisionServiceConfig(
                5_000
            );

            var bundle = new EmbeddedConsentBundle(jooqConfig, caffeineConfig);
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertNotNull(operio.consentService());
        }

        @Test
        @DisplayName("appName + discovery constructor sets appName")
        void appNameAndDiscovery() {
            var bundle = new EmbeddedConsentBundle("test-app", null);
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertEquals("test-app", operio.appName());
        }

        @Test
        @DisplayName("appName + jooq + caffeine constructor sets appName")
        void appNameJooqCaffeine() {
            var jooqConfig = new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:ctor_test_2;DB_CLOSE_DELAY=-1",
                "H2"
            );
            var caffeineConfig = new CaffeineConsentDecisionServiceConfig();

            var bundle = new EmbeddedConsentBundle(
                "another-app",
                jooqConfig,
                caffeineConfig
            );
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertEquals("another-app", operio.appName());
        }

        @Test
        @DisplayName("full constructor with all options builds successfully")
        void fullConstructor() {
            var jooqConfig = new JooqConsentAdminServiceConfig(
                "jdbc:h2:mem:ctor_test_3;DB_CLOSE_DELAY=-1",
                "H2"
            );
            var caffeineConfig = new CaffeineConsentDecisionServiceConfig(
                20_000
            );

            var bundle = new EmbeddedConsentBundle(
                "full-app",
                jooqConfig,
                caffeineConfig,
                null
            );
            Operio operio = Operio.builder().withBundle(bundle).build();
            assertEquals("full-app", operio.appName());
            assertNotNull(operio.consentService());
            assertNotNull(operio.consentAdminService());
            assertNotNull(operio.consentSyncService());
        }
    }

    // =======================================================================
    // Comparison with core bundle behavior
    // =======================================================================

    @Nested
    @DisplayName("Behavioral Parity with Core")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BehavioralParity {

        private Operio operio;

        @BeforeAll
        void buildOperio() {
            operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:embedded_bundle_parity;DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig()
                    )
                )
                .build();
        }

        @Test
        @DisplayName("write path uses sync service (not admin directly)")
        void writePathUsesSyncService() {
            // If the sync service is wired, grantConsent goes through sync
            // (which dual-writes to DB + Caffeine). We can verify this by
            // checking that the consent is readable via the decision service
            // (which only reads from Caffeine).
            var scope = ConsentScope.to("PII", "email", "User", "parity");
            operio.grantConsent("parity-user-1", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("parity-user-1", Set.of(scope));

            assertTrue(
                response.granted().contains(scope),
                "If sync is wired correctly, grants flow through " +
                    "sync → DB + Caffeine, and reads come from Caffeine"
            );
        }

        @Test
        @DisplayName("decision details include source and status metadata")
        void decisionDetailsPopulated() {
            var scope = ConsentScope.to("PII", "phone", "User", "parity");
            operio.grantConsent("parity-user-2", scope);

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("parity-user-2", Set.of(scope));

            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail, "Every evaluated scope should have a detail");
            assertEquals("GRANTED", detail.effectiveStatus());
            assertEquals("FIELD", detail.resolvedBy());
            assertSame(scope, detail.scope());
        }

        @Test
        @DisplayName("denied scopes have reason messages")
        void deniedScopesHaveReasons() {
            var scope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "parity-denied"
            );

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("parity-user-3", Set.of(scope));

            assertTrue(response.denied().contains(scope));
            assertNotNull(response.reasons().get("email"));
            assertFalse(response.reasons().get("email").isEmpty());
        }
    }

    // =======================================================================
    // Auto-registration toggle
    // =======================================================================

    @Nested
    @DisplayName("Auto-Registration Toggle")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AutoRegistrationToggle {

        @Test
        @DisplayName(
            "withAutoRegisterMetadata(false) causes grant to fail without prior registration"
        )
        void disabledAutoRegistrationFailsOnGrant() {
            Operio operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:bundle_strict_grant_" +
                                System.nanoTime() +
                                ";DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withAutoRegisterMetadata(false)
                )
                .build();

            var scope = ConsentScope.to(
                "PII",
                "lastName",
                "Employee",
                "reporting"
            );

            assertThrows(
                IllegalArgumentException.class,
                () -> operio.grantConsent("user-1", scope),
                "Should throw because metadata is not registered and auto-registration is off"
            );
        }

        @Test
        @DisplayName(
            "withAutoRegisterMetadata(false) causes revoke to fail without prior registration"
        )
        void disabledAutoRegistrationFailsOnRevoke() {
            Operio operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:bundle_strict_revoke_" +
                                System.nanoTime() +
                                ";DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withAutoRegisterMetadata(false)
                )
                .build();

            var scope = ConsentScope.to(
                "PII",
                "lastName",
                "Employee",
                "reporting"
            );

            assertThrows(
                IllegalArgumentException.class,
                () -> operio.revokeConsent("user-1", scope),
                "Should throw because metadata is not registered and auto-registration is off"
            );
        }

        @Test
        @DisplayName(
            "withAutoRegisterMetadata(true) (default) allows grant without prior registration"
        )
        void enabledAutoRegistrationSucceeds() {
            Operio operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:bundle_auto_reg_" +
                                System.nanoTime() +
                                ";DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                    // withAutoRegisterMetadata not called — defaults to true
                )
                .build();

            var scope = ConsentScope.to(
                "PII",
                "lastName",
                "Employee",
                "reporting"
            );

            assertDoesNotThrow(
                () -> operio.grantConsent("user-1", scope),
                "Should succeed because auto-registration creates the metadata"
            );

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName(
            "withAutoRegisterMetadata(false) succeeds when metadata is pre-registered via admin"
        )
        void disabledButPreRegisteredSucceeds() {
            Operio operio = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        new JooqConsentAdminServiceConfig(
                            "jdbc:h2:mem:bundle_preregistered_" +
                                System.nanoTime() +
                                ";DB_CLOSE_DELAY=-1",
                            "H2"
                        ),
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withAutoRegisterMetadata(false)
                )
                .build();

            // Pre-register metadata through admin
            operio.consentAdminService().registerCategory("PII", null);
            operio
                .consentAdminService()
                .registerField("lastName", "Employee", null);
            operio
                .consentAdminService()
                .linkFieldToCategory("lastName", "Employee", "PII");
            operio.consentAdminService().registerPurpose("reporting", null);

            var scope = ConsentScope.to(
                "PII",
                "lastName",
                "Employee",
                "reporting"
            );

            assertDoesNotThrow(() -> operio.grantConsent("user-1", scope));

            ConsentResponse response = operio
                .consentService()
                .evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("fluent setter returns same bundle instance for chaining")
        void fluentSetterReturnsThis() {
            var bundle = new EmbeddedConsentBundle();
            var returned = bundle.withAutoRegisterMetadata(false);
            assertSame(bundle, returned);
        }
    }

    // =======================================================================
    // Reconcile-on-startup tests
    // =======================================================================

    @Nested
    @DisplayName("Reconcile On Startup")
    class ReconcileOnStartup {

        @Test
        @DisplayName(
            "withReconcileOnStartup() fluent setter returns same bundle for chaining"
        )
        void fluentSetterReturnsThis() {
            var bundle = new EmbeddedConsentBundle();
            var returned = bundle.withReconcileOnStartup();
            assertSame(bundle, returned);
        }

        @Test
        @DisplayName(
            "withReconcileOnStartup(strategy) fluent setter returns same bundle for chaining"
        )
        void fluentSetterWithStrategyReturnsThis() {
            var bundle = new EmbeddedConsentBundle();
            var returned = bundle.withReconcileOnStartup(
                ReconciliationStrategy.MERGE
            );
            assertSame(bundle, returned);
        }

        @Test
        @DisplayName("startup reconciliation rebuilds cache from DB state")
        void reconcileOnStartupRebuildsCache() {
            // Phase 1: build an Operio, write some grants, then discard it
            // — this leaves the DB populated but the Caffeine cache will be
            //   lost once we create a new bundle with a fresh Caffeine.
            String jdbcUrl =
                "jdbc:h2:mem:reconcile_startup_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            var jooqConfig = new JooqConsentAdminServiceConfig(jdbcUrl, "H2");

            Operio phase1 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                )
                .build();

            var scope = ConsentScope.to(
                "PII",
                "email",
                "Customer",
                "marketing"
            );
            phase1.grantConsent("user-1", scope);

            // Sanity: grant visible in phase-1 cache
            ConsentResponse r1 = phase1
                .consentService()
                .evaluateConsent("user-1", Set.of(scope));
            assertTrue(
                r1.granted().contains(scope),
                "grant should be visible in phase-1 cache"
            );

            // Phase 2: new bundle pointing at the same DB, with
            //   withReconcileOnStartup() — the new Caffeine cache starts
            //   empty but reconciliation should restore the grant.
            Operio phase2 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withReconcileOnStartup()
                )
                .build();

            ConsentResponse r2 = phase2
                .consentService()
                .evaluateConsent("user-1", Set.of(scope));
            assertTrue(
                r2.granted().contains(scope),
                "grant should be visible after startup reconciliation"
            );
        }

        @Test
        @DisplayName("without reconcile-on-startup, new bundle has cold cache")
        void withoutReconcileOnStartupCacheIsCold() {
            // Same two-phase pattern but WITHOUT withReconcileOnStartup()
            String jdbcUrl =
                "jdbc:h2:mem:no_reconcile_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            var jooqConfig = new JooqConsentAdminServiceConfig(jdbcUrl, "H2");

            Operio phase1 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                )
                .build();

            var scope = ConsentScope.to(
                "PII",
                "email",
                "Customer",
                "marketing"
            );
            phase1.grantConsent("user-1", scope);

            // Phase 2: new bundle, same DB, NO reconciliation
            Operio phase2 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                    // withReconcileOnStartup() NOT called
                )
                .build();

            ConsentResponse r2 = phase2
                .consentService()
                .evaluateConsent("user-1", Set.of(scope));
            assertFalse(
                r2.granted().contains(scope),
                "grant should NOT be visible — cache is cold without reconciliation"
            );
        }

        @Test
        @DisplayName("MERGE strategy also restores grants on startup")
        void mergeStrategyRestoresGrants() {
            String jdbcUrl =
                "jdbc:h2:mem:merge_startup_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            var jooqConfig = new JooqConsentAdminServiceConfig(jdbcUrl, "H2");

            Operio phase1 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                )
                .build();

            var scope = ConsentScope.to("PII", "phone", "Contact", "support");
            phase1.grantConsent("user-2", scope);

            // Phase 2: reconcile with MERGE
            Operio phase2 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withReconcileOnStartup(ReconciliationStrategy.MERGE)
                )
                .build();

            ConsentResponse r2 = phase2
                .consentService()
                .evaluateConsent("user-2", Set.of(scope));
            assertTrue(
                r2.granted().contains(scope),
                "grant should be visible after MERGE reconciliation"
            );
        }

        @Test
        @DisplayName("null strategy disables startup reconciliation")
        void nullStrategyDisablesReconciliation() {
            String jdbcUrl =
                "jdbc:h2:mem:null_strategy_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            var jooqConfig = new JooqConsentAdminServiceConfig(jdbcUrl, "H2");

            Operio phase1 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                )
                .build();

            var scope = ConsentScope.to("PII", "ssn", "Employee", "compliance");
            phase1.grantConsent("emp-1", scope);

            // Phase 2: explicitly pass null strategy
            Operio phase2 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withReconcileOnStartup(null)
                )
                .build();

            ConsentResponse r2 = phase2
                .consentService()
                .evaluateConsent("emp-1", Set.of(scope));
            assertFalse(
                r2.granted().contains(scope),
                "grant should NOT be visible — null strategy means no reconciliation"
            );
        }

        @Test
        @DisplayName(
            "reconcile-on-startup restores multiple subjects and scopes"
        )
        void multiSubjectMultiScopeReconciliation() {
            String jdbcUrl =
                "jdbc:h2:mem:multi_reconcile_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            var jooqConfig = new JooqConsentAdminServiceConfig(jdbcUrl, "H2");

            Operio phase1 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    )
                )
                .build();

            var scope1 = ConsentScope.to(
                "PII",
                "firstName",
                "User",
                "analytics"
            );
            var scope2 = ConsentScope.to(
                "Financial",
                "accountNum",
                "Account",
                "billing"
            );

            phase1.grantConsent("alice", scope1);
            phase1.grantConsent("bob", scope2);

            // Phase 2: fresh cache, reconcile on startup
            Operio phase2 = Operio.builder()
                .withBundle(
                    new EmbeddedConsentBundle(
                        null,
                        jooqConfig,
                        new CaffeineConsentDecisionServiceConfig(),
                        null
                    ).withReconcileOnStartup()
                )
                .build();

            ConsentResponse aliceResult = phase2
                .consentService()
                .evaluateConsent("alice", Set.of(scope1));
            assertTrue(
                aliceResult.granted().contains(scope1),
                "alice's grant should be restored"
            );

            ConsentResponse bobResult = phase2
                .consentService()
                .evaluateConsent("bob", Set.of(scope2));
            assertTrue(
                bobResult.granted().contains(scope2),
                "bob's grant should be restored"
            );

            // Cross-check: alice should NOT have bob's scope
            ConsentResponse aliceCross = phase2
                .consentService()
                .evaluateConsent("alice", Set.of(scope2));
            assertFalse(
                aliceCross.granted().contains(scope2),
                "alice should not have bob's scope"
            );
        }
    }
}
