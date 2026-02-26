package com.koostack.operio.plugin.consent.caffeine;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.exception.OperioConfigurationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaffeineConsentDecisionServiceTest {

    private CaffeineConsentDecisionService service;
    private CaffeineConsentRepository consentRepo;
    private CaffeineFieldCategoryMappingRepository mappingRepo;

    @BeforeAll
    void setUpService() {
        var config = new CaffeineConsentDecisionServiceConfig();
        service = new CaffeineConsentDecisionService();
        service.configure(config);

        consentRepo = service.consentRepository();
        mappingRepo = service.fieldCategoryMappingRepository();
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
        @DisplayName("name is 'caffeine'")
        void nameIsCaffeine() {
            assertEquals("caffeine", service.getName());
        }

        @Test
        @DisplayName("priority is 100")
        void priorityIs100() {
            assertEquals(100, service.getPriority());
        }

        @Test
        @DisplayName(
            "configuration type is CaffeineConsentDecisionServiceConfig"
        )
        void configurationType() {
            assertEquals(
                CaffeineConsentDecisionServiceConfig.class,
                service.getConfigurationType()
            );
        }

        @Test
        @DisplayName("unconfigured service throws OperioConfigurationException")
        void unconfiguredServiceThrows() {
            var unconfigured = new CaffeineConsentDecisionService();
            assertThrows(OperioConfigurationException.class, () ->
                unconfigured.evaluateConsent("sub", Set.of())
            );
        }

        @Test
        @DisplayName("consent repository is accessible after configure")
        void repoAccessible() {
            assertNotNull(service.consentRepository());
        }

        @Test
        @DisplayName("mapping repository is accessible after configure")
        void mappingRepoAccessible() {
            assertNotNull(service.fieldCategoryMappingRepository());
        }

        @Test
        @DisplayName("isReady returns true after configure")
        void isReadyAfterConfigure() {
            assertTrue(service.isReady());
        }

        @Test
        @DisplayName("isReady returns false before configure")
        void isReadyBeforeConfigure() {
            var unconfigured = new CaffeineConsentDecisionService();
            assertFalse(unconfigured.isReady());
        }
    }

    // =======================================================================
    // AppNameAware
    // =======================================================================

    @Nested
    @DisplayName("AppNameAware")
    class AppNameAwareTests {

        @Test
        @DisplayName("default app name is 'operio'")
        void defaultAppName() {
            assertEquals("operio", service.getAppName());
        }

        @Test
        @DisplayName("setAppName updates the app name")
        void setAppNameUpdates() {
            service.setAppName("my-app");
            assertEquals("my-app", service.getAppName());
            // reset
            service.setAppName("operio");
        }

        @Test
        @DisplayName("setAppName(null) reverts to default")
        void setAppNameNullReverts() {
            service.setAppName("my-app");
            service.setAppName(null);
            assertEquals("operio", service.getAppName());
        }
    }

    // =======================================================================
    // Field-level evaluation
    // =======================================================================

    @Nested
    @DisplayName("Field-Level Evaluation")
    class FieldLevel {

        @Test
        @DisplayName("single field granted")
        void singleFieldGranted() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertEquals("user-1", response.subject());
            assertTrue(response.granted().contains(scope));
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());

            // Details
            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail);
            assertEquals("GRANTED", detail.effectiveStatus());
            assertEquals("FIELD", detail.resolvedBy());
        }

        @Test
        @DisplayName("single field revoked")
        void singleFieldRevoked() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                false
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().contains(scope));
            assertEquals(
                "Consent explicitly revoked",
                response.reasons().get("email")
            );

            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail);
            assertEquals("REVOKED", detail.effectiveStatus());
            assertEquals("FIELD", detail.resolvedBy());
        }

        @Test
        @DisplayName("single field — no record")
        void singleFieldNoRecord() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().contains(scope));
            assertEquals(
                "No consent record found",
                response.reasons().get("email")
            );

            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail);
            assertNull(detail.effectiveStatus());
            assertEquals("NONE", detail.resolvedBy());
        }

        @Test
        @DisplayName("multiple fields — mixed grant/revoke/absent")
        void multipleFieldsMixed() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "phone",
                "marketing",
                false
            );
            // ssn has no entry

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");
            var ssn = ConsentScope.to("PII", "ssn", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone, ssn)
            );

            assertEquals(1, response.granted().size());
            assertTrue(response.granted().contains(email));

            assertEquals(2, response.denied().size());
            assertTrue(response.denied().contains(phone));
            assertTrue(response.denied().contains(ssn));
        }
    }

    // =======================================================================
    // Category-sourced evaluation
    // =======================================================================

    @Nested
    @DisplayName("Category-Sourced Evaluation")
    class CategorySourced {

        @Test
        @DisplayName("category-sourced grant")
        void categorySourcedGranted() {
            consentRepo.setConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().contains(scope));

            DecisionDetail detail = response.details().get(scope);
            assertEquals("GRANTED", detail.effectiveStatus());
            assertEquals("CATEGORY", detail.resolvedBy());
        }

        @Test
        @DisplayName("fan-out covers multiple fields")
        void fanOutCoversMultipleFields() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");

            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone)
            );

            assertEquals(2, response.granted().size());
            assertTrue(response.granted().contains(email));
            assertTrue(response.granted().contains(phone));
        }

        @Test
        @DisplayName("category-sourced revoke")
        void categorySourcedRevoked() {
            consentRepo.setConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                false,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.denied().contains(scope));
            DecisionDetail detail = response.details().get(scope);
            assertEquals("REVOKED", detail.effectiveStatus());
            assertEquals("CATEGORY", detail.resolvedBy());
        }

        @Test
        @DisplayName("mixed sources — field and category")
        void mixedSources() {
            // Field-level grant
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            // Category-sourced grant
            consentRepo.setConsent(
                "user-1",
                "User",
                "phone",
                "marketing",
                true,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone)
            );

            assertEquals(2, response.granted().size());

            assertEquals("FIELD", response.details().get(email).resolvedBy());
            assertEquals(
                "CATEGORY",
                response.details().get(phone).resolvedBy()
            );
        }
    }

    // =======================================================================
    // Category fan-out precedence
    // =======================================================================

    @Nested
    @DisplayName("Category Fan-Out Precedence")
    class CategoryFanOutPrecedence {

        @Test
        @DisplayName("field-level survives category fan-out")
        void fieldLevelSurvivesCategoryFanOut() {
            // Set field-level grant for email
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

            // Now fan out a category-level REVOKE to the same field
            mappingRepo.addFieldToCategory("User", "email", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                fields
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            // Field-level grant should survive the category revoke
            assertTrue(response.granted().contains(scope));
            assertEquals("FIELD", response.details().get(scope).resolvedBy());
        }

        @Test
        @DisplayName("mixed precedence during evaluation")
        void mixedPrecedenceDuringEvaluation() {
            // Field-level grant for email
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

            // Category fan-out revoke for email + phone
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone)
            );

            // email: field-level grant (survives category revoke)
            assertTrue(response.granted().contains(email));
            assertEquals("FIELD", response.details().get(email).resolvedBy());

            // phone: category-level revoke (no field-level to override)
            assertTrue(response.denied().contains(phone));
            assertEquals(
                "CATEGORY",
                response.details().get(phone).resolvedBy()
            );
        }

        @Test
        @DisplayName("all field-sourced — category fan-out is no-op")
        void allFieldSourcedNoop() {
            // Field-level grants for both
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "phone",
                "marketing",
                true
            );

            // Category fan-out revoke — should be completely skipped
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone)
            );

            assertEquals(2, response.granted().size());
            assertTrue(response.granted().contains(email));
            assertTrue(response.granted().contains(phone));
        }
    }

    // =======================================================================
    // Purpose isolation
    // =======================================================================

    @Nested
    @DisplayName("Purpose Isolation")
    class PurposeIsolation {

        @Test
        @DisplayName("field consent is isolated per purpose")
        void fieldPurposeIsolation() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

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

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertTrue(response.granted().contains(marketing));
            assertTrue(response.denied().contains(analytics));
        }

        @Test
        @DisplayName("multiple purposes granted independently")
        void multiplePurposesGranted() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "analytics",
                true
            );

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

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertEquals(2, response.granted().size());
        }
    }

    // =======================================================================
    // Subject isolation
    // =======================================================================

    @Nested
    @DisplayName("Subject Isolation")
    class SubjectIsolation {

        @Test
        @DisplayName("different subjects are isolated")
        void subjectIsolation() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            ConsentResponse r1 = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = service.evaluateConsent(
                "user-2",
                Set.of(scope)
            );

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
        }

        @Test
        @DisplayName("two subjects with different consent")
        void twoSubjectsDifferentConsent() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-2",
                "User",
                "email",
                "marketing",
                false
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            ConsentResponse r1 = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = service.evaluateConsent(
                "user-2",
                Set.of(scope)
            );

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
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
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "Contact",
                "email",
                "marketing",
                false
            );

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

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(userEmail, contactEmail)
            );

            assertTrue(response.granted().contains(userEmail));
            assertTrue(response.denied().contains(contactEmail));
        }

        @Test
        @DisplayName("null entity uses _default_ sentinel")
        void nullEntityUsesSentinel() {
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "email",
                "marketing",
                true
            );

            var scope = ConsentScope.to("PII", "email", "marketing");
            ConsentResponse response = service.evaluateConsent(
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
        @DisplayName("empty request set returns empty response")
        void emptyRequestSet() {
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of()
            );

            assertEquals("user-1", response.subject());
            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());
            assertTrue(response.details().isEmpty());
        }

        @Test
        @DisplayName("null request set returns empty response")
        void nullRequestSet() {
            ConsentResponse response = service.evaluateConsent("user-1", null);

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName("unknown subject returns all denied")
        void unknownSubject() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "nonexistent",
                Set.of(scope)
            );

            assertTrue(response.denied().contains(scope));
            assertEquals(
                "No consent record found",
                response.reasons().get("email")
            );
        }

        @Test
        @DisplayName("reason messages are correct for revoked vs absent")
        void reasonMessages() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                false
            );
            // phone has no entry

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone)
            );

            assertEquals(
                "Consent explicitly revoked",
                response.reasons().get("email")
            );
            assertEquals(
                "No consent record found",
                response.reasons().get("phone")
            );
        }
    }

    // =======================================================================
    // TTL / Expiry
    // =======================================================================

    @Nested
    @DisplayName("TTL Expiry")
    class TtlExpiry {

        @Test
        @DisplayName("future expiry — entry is present and granted")
        void futureExpiryGranted() {
            Instant future = Instant.now().plus(Duration.ofHours(1));
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true,
                future
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().contains(scope));

            // DecisionDetail should include expiresAt
            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail.expiresAt());
            assertEquals(future, detail.expiresAt());
        }

        @Test
        @DisplayName("past expiry — entry is not written")
        void pastExpiryNotWritten() {
            Instant past = Instant.now().minus(Duration.ofHours(1));
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true,
                past
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            // Entry should not have been written (past expiry skipped)
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("null expiry means no expiration")
        void nullExpiryNoExpiration() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true,
                null
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().contains(scope));

            DecisionDetail detail = response.details().get(scope);
            assertNull(detail.expiresAt());
        }

        @Test
        @DisplayName("category fan-out with past expiry is no-op")
        void categoryFanOutPastExpiry() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");

            Instant past = Instant.now().minus(Duration.ofHours(1));
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                past,
                fields
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.denied().contains(scope));
        }
    }

    // =======================================================================
    // Large batch
    // =======================================================================

    @Nested
    @DisplayName("Large Batch")
    class LargeBatch {

        @Test
        @DisplayName("100 scopes evaluated correctly")
        void hundredScopes() {
            Set<ConsentScope> scopes = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                String field = "field_" + i;
                consentRepo.setFieldConsent(
                    "user-1",
                    "Entity",
                    field,
                    "purpose",
                    i % 2 == 0
                );
                scopes.add(ConsentScope.to("CAT", field, "Entity", "purpose"));
            }

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                scopes
            );

            assertEquals(50, response.granted().size());
            assertEquals(50, response.denied().size());
            assertEquals(100, response.details().size());
        }
    }

    // =======================================================================
    // Details map
    // =======================================================================

    @Nested
    @DisplayName("Details Map")
    class DetailsMap {

        @Test
        @DisplayName("details map contains all scopes")
        void detailsForAllScopes() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "phone",
                "marketing",
                false
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");
            var ssn = ConsentScope.to("PII", "ssn", "User", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, phone, ssn)
            );

            Map<ConsentScope, DecisionDetail> details = response.details();
            assertEquals(3, details.size());
            assertTrue(details.containsKey(email));
            assertTrue(details.containsKey(phone));
            assertTrue(details.containsKey(ssn));

            assertEquals("GRANTED", details.get(email).effectiveStatus());
            assertEquals("REVOKED", details.get(phone).effectiveStatus());
            assertNull(details.get(ssn).effectiveStatus());
        }

        @Test
        @DisplayName("detail scope matches request scope")
        void detailScopeMatchesRequest() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            DecisionDetail detail = response.details().get(scope);
            assertNotNull(detail);
            assertSame(scope, detail.scope());
        }
    }

    // =======================================================================
    // Repository housekeeping
    // =======================================================================

    @Nested
    @DisplayName("Repository Housekeeping")
    class RepositoryHousekeeping {

        @Test
        @DisplayName("removeAllConsent removes all entries for a subject")
        void removeAllConsent() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "phone",
                "analytics",
                true
            );
            consentRepo.setFieldConsent(
                "user-2",
                "User",
                "email",
                "marketing",
                true
            );

            long removed = consentRepo.removeAllConsent("user-1");
            assertEquals(2, removed);

            // user-1 entries gone
            assertFalse(
                consentRepo.exists("user-1", "User", "email", "marketing")
            );
            assertFalse(
                consentRepo.exists("user-1", "User", "phone", "analytics")
            );

            // user-2 still present
            assertTrue(
                consentRepo.exists("user-2", "User", "email", "marketing")
            );
        }

        @Test
        @DisplayName("invalidateAll clears the entire cache")
        void invalidateAllClears() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            consentRepo.setFieldConsent(
                "user-2",
                "User",
                "phone",
                "analytics",
                true
            );

            consentRepo.invalidateAll();
            consentRepo.cleanUp();

            assertEquals(0, consentRepo.estimatedSize());
        }

        @Test
        @DisplayName("exists returns false after remove")
        void existsAfterRemove() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            assertTrue(
                consentRepo.exists("user-1", "User", "email", "marketing")
            );

            consentRepo.removeConsent("user-1", "User", "email", "marketing");
            assertFalse(
                consentRepo.exists("user-1", "User", "email", "marketing")
            );
        }
    }

    // =======================================================================
    // Field-category mapping repository
    // =======================================================================

    @Nested
    @DisplayName("Field-Category Mapping Repository")
    class MappingTests {

        @Test
        @DisplayName("addFieldToCategory creates bidirectional mapping")
        void bidirectionalMapping() {
            mappingRepo.addFieldToCategory("User", "email", "PII");

            assertTrue(mappingRepo.isMapped("User", "email", "PII"));
            assertTrue(
                mappingRepo
                    .getCategoriesForField("User", "email")
                    .contains("PII")
            );
            assertTrue(
                mappingRepo.getFieldsForCategory("PII").contains("User/email")
            );
        }

        @Test
        @DisplayName("addFieldToCategory is idempotent")
        void addIsIdempotent() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "email", "PII");

            assertEquals(1, mappingRepo.getFieldsForCategory("PII").size());
            assertEquals(
                1,
                mappingRepo.getCategoriesForField("User", "email").size()
            );
        }

        @Test
        @DisplayName("removeFieldFromCategory removes mapping")
        void removeMapping() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.removeFieldFromCategory("User", "email", "PII");

            assertFalse(mappingRepo.isMapped("User", "email", "PII"));
            assertTrue(
                mappingRepo.getCategoriesForField("User", "email").isEmpty()
            );
            assertTrue(mappingRepo.getFieldsForCategory("PII").isEmpty());
        }

        @Test
        @DisplayName("field can belong to multiple categories")
        void fieldMultipleCategories() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "email", "CONTACT");

            Set<String> categories = mappingRepo.getCategoriesForField(
                "User",
                "email"
            );
            assertEquals(2, categories.size());
            assertTrue(categories.contains("PII"));
            assertTrue(categories.contains("CONTACT"));
        }

        @Test
        @DisplayName("category can contain multiple fields")
        void categoryMultipleFields() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");
            mappingRepo.addFieldToCategory("User", "ssn", "PII");

            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            assertEquals(3, fields.size());
        }

        @Test
        @DisplayName("removeAllMappingsForField cleans up everything")
        void removeAllForField() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "email", "CONTACT");

            int removed = mappingRepo.removeAllMappingsForField(
                "User",
                "email"
            );
            assertEquals(2, removed);

            assertTrue(
                mappingRepo.getCategoriesForField("User", "email").isEmpty()
            );
            assertFalse(
                mappingRepo.getFieldsForCategory("PII").contains("User/email")
            );
            assertFalse(
                mappingRepo
                    .getFieldsForCategory("CONTACT")
                    .contains("User/email")
            );
        }

        @Test
        @DisplayName("removeAllMappingsForCategory cleans up everything")
        void removeAllForCategory() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");

            int removed = mappingRepo.removeAllMappingsForCategory("PII");
            assertEquals(2, removed);

            assertTrue(mappingRepo.getFieldsForCategory("PII").isEmpty());
            assertFalse(
                mappingRepo
                    .getCategoriesForField("User", "email")
                    .contains("PII")
            );
        }

        @Test
        @DisplayName("null entity uses _default_ sentinel in qualified name")
        void nullEntitySentinel() {
            mappingRepo.addFieldToCategory(null, "email", "PII");

            assertTrue(
                mappingRepo
                    .getFieldsForCategory("PII")
                    .contains("_default_/email")
            );
            assertTrue(mappingRepo.isMapped(null, "email", "PII"));
        }

        @Test
        @DisplayName("clear removes all mappings")
        void clearRemovesAll() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "CONTACT");

            mappingRepo.clear();

            assertEquals(0, mappingRepo.fieldCount());
            assertEquals(0, mappingRepo.categoryCount());
        }
    }

    // =======================================================================
    // Config validation
    // =======================================================================

    @Nested
    @DisplayName("Config Validation")
    class ConfigValidation {

        @Test
        @DisplayName("default config uses DEFAULT_MAX_SIZE")
        void defaultConfig() {
            var config = new CaffeineConsentDecisionServiceConfig();
            assertEquals(
                CaffeineConsentDecisionServiceConfig.DEFAULT_MAX_SIZE,
                config.maxSize()
            );
        }

        @Test
        @DisplayName("custom maxSize is respected")
        void customMaxSize() {
            var config = new CaffeineConsentDecisionServiceConfig(50_000);
            assertEquals(50_000, config.maxSize());
        }

        @Test
        @DisplayName("zero maxSize throws IllegalArgumentException")
        void zeroMaxSizeThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                new CaffeineConsentDecisionServiceConfig(0)
            );
        }

        @Test
        @DisplayName("negative maxSize throws IllegalArgumentException")
        void negativeMaxSizeThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                new CaffeineConsentDecisionServiceConfig(-1)
            );
        }
    }

    // =======================================================================
    // Tenant-aware consent keys
    // =======================================================================

    @Nested
    @DisplayName("Tenant-Aware Consent Keys")
    class TenantAwareKeys {

        @Test
        @DisplayName("keyPrefix includes tenantId when TenantContext is active")
        void keyPrefixWithTenant() throws Exception {
            TenantContext.callWhere("acme", () -> {
                assertEquals("acme:", consentRepo.keyPrefix());
                return null;
            });
        }

        @Test
        @DisplayName("keyPrefix is empty when no TenantContext is active")
        void keyPrefixWithoutTenant() {
            assertEquals("", consentRepo.keyPrefix());
        }

        @Test
        @DisplayName(
            "consentKey includes tenant segment when TenantContext is active"
        )
        void consentKeyWithTenant() throws Exception {
            TenantContext.callWhere("acme", () -> {
                assertEquals(
                    "acme:user-1:_default_/ssn:marketing",
                    consentRepo.consentKey("user-1", null, "ssn", "marketing")
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "consentKey omits tenant segment when no TenantContext is active"
        )
        void consentKeyWithoutTenant() {
            assertEquals(
                "user-1:_default_/ssn:marketing",
                consentRepo.consentKey("user-1", null, "ssn", "marketing")
            );
        }

        @Test
        @DisplayName(
            "write and read within a tenant context are isolated from default namespace"
        )
        void tenantWriteReadIsolation() throws Exception {
            // Write in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Read outside tenant context — should NOT see acme's data
            assertFalse(consentRepo.exists("user-1", null, "ssn", "marketing"));
            assertTrue(
                consentRepo
                    .getConsent("user-1", null, "ssn", "marketing")
                    .isEmpty()
            );

            // Read inside tenant "acme" — should see the data
            TenantContext.callWhere("acme", () -> {
                assertTrue(
                    consentRepo.exists("user-1", null, "ssn", "marketing")
                );
                Optional<String> status = consentRepo.getConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing"
                );
                assertTrue(status.isPresent());
                assertEquals("GRANTED", status.get());
                return null;
            });
        }

        @Test
        @DisplayName("different tenants do not see each other's data")
        void crossTenantIsolation() throws Exception {
            // Tenant "acme" grants consent
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Tenant "globex" revokes same scope
            TenantContext.callWhere("globex", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    false
                );
                return null;
            });

            // Each tenant sees only its own decision
            TenantContext.callWhere("acme", () -> {
                Optional<String> status = consentRepo.getConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing"
                );
                assertEquals("GRANTED", status.orElse(null));
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                Optional<String> status = consentRepo.getConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing"
                );
                assertEquals("REVOKED", status.orElse(null));
                return null;
            });
        }

        @Test
        @DisplayName("evaluateScopes respects tenant context")
        void evaluateScopesWithTenant() throws Exception {
            ConsentScope scope = ConsentScope.to(
                null,
                "ssn",
                null,
                "marketing"
            );

            // Grant in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Evaluate inside "acme" — should be granted
            TenantContext.callWhere("acme", () -> {
                Map<ConsentScope, DecisionDetail> result =
                    consentRepo.evaluateScopes("user-1", Set.of(scope));
                DecisionDetail detail = result.get(scope);
                assertNotNull(detail);
                assertEquals("GRANTED", detail.effectiveStatus());
                return null;
            });

            // Evaluate inside "globex" — no record
            TenantContext.callWhere("globex", () -> {
                Map<ConsentScope, DecisionDetail> result =
                    consentRepo.evaluateScopes("user-1", Set.of(scope));
                DecisionDetail detail = result.get(scope);
                assertNotNull(detail);
                assertNull(detail.effectiveStatus());
                return null;
            });

            // Evaluate outside tenant context — no record
            Map<ConsentScope, DecisionDetail> result =
                consentRepo.evaluateScopes("user-1", Set.of(scope));
            DecisionDetail detail = result.get(scope);
            assertNotNull(detail);
            assertNull(detail.effectiveStatus());
        }

        @Test
        @DisplayName("removeAllConsent only removes keys for the active tenant")
        void removeAllConsentTenantIsolation() throws Exception {
            // Write data in two tenants
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "marketing",
                    true
                );
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Remove all for user-1 in "acme" only
            TenantContext.callWhere("acme", () -> {
                long removed = consentRepo.removeAllConsent("user-1");
                assertEquals(2, removed);
                return null;
            });

            // "acme" data is gone
            TenantContext.callWhere("acme", () -> {
                assertFalse(
                    consentRepo.exists("user-1", null, "ssn", "marketing")
                );
                assertFalse(
                    consentRepo.exists("user-1", null, "email", "marketing")
                );
                return null;
            });

            // "globex" data is untouched
            TenantContext.callWhere("globex", () -> {
                assertTrue(
                    consentRepo.exists("user-1", null, "ssn", "marketing")
                );
                return null;
            });
        }

        @Test
        @DisplayName("category fan-out writes are tenant-scoped")
        void categoryFanOutWithTenant() throws Exception {
            Set<String> fields = Set.of("_default_/ssn", "_default_/email");

            // Fan out in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setCategoryConsentForFields(
                    "user-1",
                    "marketing",
                    true,
                    fields
                );
                return null;
            });

            // Verify keys exist in "acme"
            TenantContext.callWhere("acme", () -> {
                assertTrue(
                    consentRepo.exists("user-1", null, "ssn", "marketing")
                );
                assertTrue(
                    consentRepo.exists("user-1", null, "email", "marketing")
                );
                return null;
            });

            // Verify keys do NOT exist outside tenant context
            assertFalse(consentRepo.exists("user-1", null, "ssn", "marketing"));
            assertFalse(
                consentRepo.exists("user-1", null, "email", "marketing")
            );

            // Verify keys do NOT exist in a different tenant
            TenantContext.callWhere("globex", () -> {
                assertFalse(
                    consentRepo.exists("user-1", null, "ssn", "marketing")
                );
                assertFalse(
                    consentRepo.exists("user-1", null, "email", "marketing")
                );
                return null;
            });
        }

        @Test
        @DisplayName("full evaluation through service is tenant-isolated")
        void serviceEvaluationTenantIsolation() throws Exception {
            ConsentScope scope = ConsentScope.to(
                "PII",
                "ssn",
                "User",
                "marketing"
            );

            // Grant in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    "User",
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Evaluate through service in "acme" — granted
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(response.granted().contains(scope));
                assertFalse(response.denied().contains(scope));
                return null;
            });

            // Evaluate through service in "globex" — denied (no record)
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertFalse(response.granted().contains(scope));
                assertTrue(response.denied().contains(scope));
                return null;
            });

            // Evaluate through service with no tenant — denied (no record)
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertFalse(response.granted().contains(scope));
            assertTrue(response.denied().contains(scope));
        }
    }

    // =======================================================================
    // Tenant-aware field-category mappings
    // =======================================================================

    @Nested
    @DisplayName("Tenant-Aware Field-Category Mappings")
    class TenantAwareMappings {

        @Test
        @DisplayName("keyPrefix includes tenantId when TenantContext is active")
        void keyPrefixWithTenant() throws Exception {
            TenantContext.callWhere("acme", () -> {
                assertEquals("acme:", mappingRepo.keyPrefix());
                return null;
            });
        }

        @Test
        @DisplayName("keyPrefix is empty when no TenantContext is active")
        void keyPrefixWithoutTenant() {
            assertEquals("", mappingRepo.keyPrefix());
        }

        @Test
        @DisplayName(
            "write and read within a tenant context are isolated from default namespace"
        )
        void tenantWriteReadIsolation() throws Exception {
            // Write mapping in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });

            // Read outside tenant context — should NOT see acme's mapping
            assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
            assertTrue(
                mappingRepo.getCategoriesForField(null, "ssn").isEmpty()
            );
            assertTrue(mappingRepo.getFieldsForCategory("PII").isEmpty());

            // Read inside tenant "acme" — should see the mapping
            TenantContext.callWhere("acme", () -> {
                assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
                assertTrue(
                    mappingRepo
                        .getCategoriesForField(null, "ssn")
                        .contains("PII")
                );
                assertTrue(
                    mappingRepo
                        .getFieldsForCategory("PII")
                        .contains("_default_/ssn")
                );
                return null;
            });
        }

        @Test
        @DisplayName("different tenants do not see each other's mappings")
        void crossTenantIsolation() throws Exception {
            // Tenant "acme" maps ssn → PII
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });

            // Tenant "globex" maps email → Contact
            TenantContext.callWhere("globex", () -> {
                mappingRepo.addFieldToCategory(null, "email", "Contact");
                return null;
            });

            // Acme sees only its mapping
            TenantContext.callWhere("acme", () -> {
                assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
                assertFalse(mappingRepo.isMapped(null, "email", "Contact"));
                return null;
            });

            // Globex sees only its mapping
            TenantContext.callWhere("globex", () -> {
                assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
                assertTrue(mappingRepo.isMapped(null, "email", "Contact"));
                return null;
            });
        }

        @Test
        @DisplayName("removeAllMappingsForField only affects active tenant")
        void removeForFieldTenantIsolation() throws Exception {
            // Both tenants map ssn → PII
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });
            TenantContext.callWhere("globex", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });

            // Remove in "acme" only
            TenantContext.callWhere("acme", () -> {
                int removed = mappingRepo.removeAllMappingsForField(
                    null,
                    "ssn"
                );
                assertEquals(1, removed);
                return null;
            });

            // Acme's mapping is gone
            TenantContext.callWhere("acme", () -> {
                assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
                return null;
            });

            // Globex's mapping is untouched
            TenantContext.callWhere("globex", () -> {
                assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
                return null;
            });
        }

        @Test
        @DisplayName("removeAllMappingsForCategory only affects active tenant")
        void removeForCategoryTenantIsolation() throws Exception {
            // Both tenants have PII category with different fields
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                mappingRepo.addFieldToCategory(null, "email", "PII");
                return null;
            });
            TenantContext.callWhere("globex", () -> {
                mappingRepo.addFieldToCategory(null, "dob", "PII");
                return null;
            });

            // Remove category "PII" in "acme" only
            TenantContext.callWhere("acme", () -> {
                int removed = mappingRepo.removeAllMappingsForCategory("PII");
                assertEquals(2, removed);
                return null;
            });

            // Acme's PII category is gone
            TenantContext.callWhere("acme", () -> {
                assertTrue(mappingRepo.getFieldsForCategory("PII").isEmpty());
                assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
                assertFalse(mappingRepo.isMapped(null, "email", "PII"));
                return null;
            });

            // Globex's PII mapping is untouched
            TenantContext.callWhere("globex", () -> {
                assertTrue(mappingRepo.isMapped(null, "dob", "PII"));
                assertEquals(1, mappingRepo.getFieldsForCategory("PII").size());
                return null;
            });
        }

        @Test
        @DisplayName("removeFieldFromCategory is tenant-scoped")
        void removeFieldFromCategoryTenantIsolation() throws Exception {
            // Both tenants map ssn → PII
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });
            TenantContext.callWhere("globex", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                return null;
            });

            // Remove single mapping in "acme"
            TenantContext.callWhere("acme", () -> {
                mappingRepo.removeFieldFromCategory(null, "ssn", "PII");
                assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
                return null;
            });

            // Globex still has it
            TenantContext.callWhere("globex", () -> {
                assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
                return null;
            });
        }

        @Test
        @DisplayName("entity-qualified fields are isolated across tenants")
        void entityQualifiedTenantIsolation() throws Exception {
            // Acme maps User/email → PII, Globex maps Contact/email → PII
            TenantContext.callWhere("acme", () -> {
                mappingRepo.addFieldToCategory("User", "email", "PII");
                return null;
            });
            TenantContext.callWhere("globex", () -> {
                mappingRepo.addFieldToCategory("Contact", "email", "PII");
                return null;
            });

            TenantContext.callWhere("acme", () -> {
                assertTrue(mappingRepo.isMapped("User", "email", "PII"));
                assertFalse(mappingRepo.isMapped("Contact", "email", "PII"));
                assertEquals(
                    Set.of("User/email"),
                    mappingRepo.getFieldsForCategory("PII")
                );
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                assertFalse(mappingRepo.isMapped("User", "email", "PII"));
                assertTrue(mappingRepo.isMapped("Contact", "email", "PII"));
                assertEquals(
                    Set.of("Contact/email"),
                    mappingRepo.getFieldsForCategory("PII")
                );
                return null;
            });
        }
    }

    // =======================================================================
    // Critical Multi-Tenant Isolation Lifecycle (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Critical Multi-Tenant Isolation Lifecycle")
    class CriticalMultiTenantIsolation {

        @Test
        @DisplayName(
            "full lifecycle: create A & B → register schema in A → " +
                "grant consent for user-123 in A → evaluate in B returns denied → " +
                "evaluate in A returns granted → delete A → A gone, B unaffected"
        )
        void fullTenantLifecycle() throws Exception {
            ConsentScope ssnScope = ConsentScope.to(
                "PII",
                "ssn",
                "User",
                "marketing"
            );
            ConsentScope emailScope = ConsentScope.to(
                "PII",
                "email",
                "User",
                "marketing"
            );

            // --- "Create" tenant A: register schema (mappings) and grant consent ---
            TenantContext.callWhere("tenantA", () -> {
                // Register field-category mappings (simulates schema registration)
                mappingRepo.addFieldToCategory("User", "ssn", "PII");
                mappingRepo.addFieldToCategory("User", "email", "PII");

                // Grant consent for user-123 on both fields
                consentRepo.setFieldConsent(
                    "user-123",
                    "User",
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setFieldConsent(
                    "user-123",
                    "User",
                    "email",
                    "marketing",
                    true
                );
                return null;
            });

            // --- "Create" tenant B: separate mappings and partial consent ---
            TenantContext.callWhere("tenantB", () -> {
                mappingRepo.addFieldToCategory("User", "ssn", "PII");
                // Grant only ssn, NOT email
                consentRepo.setFieldConsent(
                    "user-123",
                    "User",
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // --- Evaluate in B → ssn granted, email denied ---
            TenantContext.callWhere("tenantB", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(
                    response.granted().contains(ssnScope),
                    "Tenant B should have ssn granted"
                );
                assertTrue(
                    response.denied().contains(emailScope),
                    "Tenant B should NOT have email granted"
                );
                return null;
            });

            // --- Evaluate in A → both granted ---
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(
                    response.granted().contains(ssnScope),
                    "Tenant A should have ssn granted"
                );
                assertTrue(
                    response.granted().contains(emailScope),
                    "Tenant A should have email granted"
                );
                assertEquals(2, response.granted().size());
                assertTrue(response.denied().isEmpty());
                return null;
            });

            // --- "Delete" tenant A: remove all consent and mappings ---
            TenantContext.callWhere("tenantA", () -> {
                long removed = consentRepo.removeAllConsent("user-123");
                assertEquals(
                    2,
                    removed,
                    "Should remove 2 consent entries for tenantA/user-123"
                );
                mappingRepo.removeAllMappingsForField("User", "ssn");
                mappingRepo.removeAllMappingsForField("User", "email");
                return null;
            });

            // --- Verify tenant A data is gone ---
            TenantContext.callWhere("tenantA", () -> {
                // Consent data is gone
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(
                    response.denied().contains(ssnScope),
                    "Tenant A ssn should be denied after delete"
                );
                assertTrue(
                    response.denied().contains(emailScope),
                    "Tenant A email should be denied after delete"
                );

                // Mappings are gone
                assertFalse(
                    mappingRepo.isMapped("User", "ssn", "PII"),
                    "Tenant A ssn→PII mapping should be gone"
                );
                assertFalse(
                    mappingRepo.isMapped("User", "email", "PII"),
                    "Tenant A email→PII mapping should be gone"
                );
                assertTrue(
                    mappingRepo.getCategoriesForField("User", "ssn").isEmpty()
                );
                assertTrue(mappingRepo.getFieldsForCategory("PII").isEmpty());
                return null;
            });

            // --- Verify tenant B is completely unaffected ---
            TenantContext.callWhere("tenantB", () -> {
                // Consent: ssn still granted, email still denied
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(
                    response.granted().contains(ssnScope),
                    "Tenant B ssn should still be granted after deleting A"
                );
                assertTrue(
                    response.denied().contains(emailScope),
                    "Tenant B email should still be denied (was never granted)"
                );

                // Mappings: ssn→PII still exists
                assertTrue(
                    mappingRepo.isMapped("User", "ssn", "PII"),
                    "Tenant B ssn→PII mapping should be untouched"
                );
                return null;
            });
        }

        @Test
        @DisplayName("three tenants with same subject diverge independently")
        void threeTenantsIndependentDecisions() throws Exception {
            ConsentScope scope = ConsentScope.to(
                "PII",
                "ssn",
                null,
                "marketing"
            );

            // Alpha: GRANTED
            TenantContext.callWhere("alpha", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Beta: REVOKED
            TenantContext.callWhere("beta", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    false
                );
                return null;
            });

            // Gamma: no record at all

            // Verify each tenant independently
            TenantContext.callWhere("alpha", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(
                    r.granted().contains(scope),
                    "alpha: user-1 should be GRANTED"
                );
                return null;
            });

            TenantContext.callWhere("beta", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(
                    r.denied().contains(scope),
                    "beta: user-1 should be DENIED (revoked)"
                );
                assertEquals(
                    "Consent explicitly revoked",
                    r.reasons().get("ssn")
                );
                return null;
            });

            TenantContext.callWhere("gamma", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(
                    r.denied().contains(scope),
                    "gamma: user-1 should be DENIED (no record)"
                );
                assertEquals("No consent record found", r.reasons().get("ssn"));
                return null;
            });
        }

        @Test
        @DisplayName(
            "entity-qualified fields remain isolated across tenants through full service"
        )
        void entityQualifiedFieldsTenantIsolation() throws Exception {
            ConsentScope userEmail = ConsentScope.to(
                null,
                "email",
                "User",
                "marketing"
            );
            ConsentScope contactEmail = ConsentScope.to(
                null,
                "email",
                "Contact",
                "marketing"
            );

            // Tenant A: grant User/email, Tenant B: grant Contact/email
            TenantContext.callWhere("tenantA", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    "User",
                    "email",
                    "marketing",
                    true
                );
                return null;
            });
            TenantContext.callWhere("tenantB", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    "Contact",
                    "email",
                    "marketing",
                    true
                );
                return null;
            });

            // Tenant A: User/email granted, Contact/email denied
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(userEmail, contactEmail)
                );
                assertTrue(r.granted().contains(userEmail));
                assertTrue(r.denied().contains(contactEmail));
                return null;
            });

            // Tenant B: User/email denied, Contact/email granted
            TenantContext.callWhere("tenantB", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(userEmail, contactEmail)
                );
                assertTrue(r.denied().contains(userEmail));
                assertTrue(r.granted().contains(contactEmail));
                return null;
            });
        }

        @Test
        @DisplayName(
            "category fan-out + delete lifecycle is fully tenant-isolated"
        )
        void categoryFanOutDeleteLifecycle() throws Exception {
            ConsentScope ssn = ConsentScope.to("PII", "ssn", null, "analytics");
            ConsentScope email = ConsentScope.to(
                "PII",
                "email",
                null,
                "analytics"
            );
            Set<String> qualifiedFields = Set.of(
                "_default_/ssn",
                "_default_/email"
            );

            // Tenant A: fan-out grant via category
            TenantContext.callWhere("tenantA", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                mappingRepo.addFieldToCategory(null, "email", "PII");
                consentRepo.setCategoryConsentForFields(
                    "user-1",
                    "analytics",
                    true,
                    qualifiedFields
                );
                return null;
            });

            // Tenant B: fan-out grant via category (same scopes)
            TenantContext.callWhere("tenantB", () -> {
                mappingRepo.addFieldToCategory(null, "ssn", "PII");
                mappingRepo.addFieldToCategory(null, "email", "PII");
                consentRepo.setCategoryConsentForFields(
                    "user-1",
                    "analytics",
                    true,
                    qualifiedFields
                );
                return null;
            });

            // Both tenants see grants
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );
                assertEquals(2, r.granted().size());
                return null;
            });
            TenantContext.callWhere("tenantB", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );
                assertEquals(2, r.granted().size());
                return null;
            });

            // Delete tenant A's data
            TenantContext.callWhere("tenantA", () -> {
                consentRepo.removeAllConsent("user-1");
                mappingRepo.removeAllMappingsForCategory("PII");
                return null;
            });

            // Tenant A is gone
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );
                assertEquals(
                    2,
                    r.denied().size(),
                    "Tenant A should be fully denied after delete"
                );
                assertTrue(
                    mappingRepo.getFieldsForCategory("PII").isEmpty(),
                    "Tenant A PII category should have no fields"
                );
                return null;
            });

            // Tenant B is untouched
            TenantContext.callWhere("tenantB", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );
                assertEquals(
                    2,
                    r.granted().size(),
                    "Tenant B should still have both grants"
                );
                assertEquals(
                    2,
                    mappingRepo.getFieldsForCategory("PII").size(),
                    "Tenant B PII category should still have 2 fields"
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "default namespace (no tenant) is independent of all tenants"
        )
        void defaultNamespaceIndependent() throws Exception {
            ConsentScope scope = ConsentScope.to(
                null,
                "ssn",
                null,
                "marketing"
            );

            // Write in default namespace (no tenant)
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true
            );
            mappingRepo.addFieldToCategory(null, "ssn", "PII");

            // Write different decision in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    false
                );
                mappingRepo.addFieldToCategory(null, "ssn", "Sensitive");
                return null;
            });

            // Default namespace: granted
            ConsentResponse defaultResp = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(
                defaultResp.granted().contains(scope),
                "Default namespace should be GRANTED"
            );
            assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
            assertFalse(mappingRepo.isMapped(null, "ssn", "Sensitive"));

            // Acme: revoked
            TenantContext.callWhere("acme", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(
                    r.denied().contains(scope),
                    "Acme should be DENIED (revoked)"
                );
                assertFalse(mappingRepo.isMapped(null, "ssn", "PII"));
                assertTrue(mappingRepo.isMapped(null, "ssn", "Sensitive"));
                return null;
            });

            // Delete acme — default should be unaffected
            TenantContext.callWhere("acme", () -> {
                consentRepo.removeAllConsent("user-1");
                mappingRepo.removeAllMappingsForField(null, "ssn");
                return null;
            });

            // Default still has its data
            ConsentResponse afterDelete = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(
                afterDelete.granted().contains(scope),
                "Default namespace should still be GRANTED after acme deletion"
            );
            assertTrue(mappingRepo.isMapped(null, "ssn", "PII"));
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
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("isolation check 2 — writes don't leak to next test")
        void emptyRun2() {
            consentRepo.setFieldConsent(
                "user-1",
                "User",
                "email",
                "marketing",
                true
            );
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("isolation check 3 — previous test's data is gone")
        void emptyRun3() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            assertTrue(response.denied().contains(scope));
        }
    }
}
