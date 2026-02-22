package com.koostack.operio.plugin.consent.caffeine;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.exception.OperioConfigurationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
        @DisplayName("configuration type is CaffeineConsentDecisionServiceConfig")
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
            assertThrows(
                OperioConfigurationException.class,
                () -> unconfigured.evaluateConsent("sub", Set.of())
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
                "user-1", "User", "email", "marketing", true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "User", "email", "marketing", false
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "User", "phone", "marketing", false
            );
            // ssn has no entry

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");
            var ssn = ConsentScope.to("PII", "ssn", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone, ssn));

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
                "user-1", "User", "email", "marketing", true,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "marketing", true, fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone));

            assertEquals(2, response.granted().size());
            assertTrue(response.granted().contains(email));
            assertTrue(response.granted().contains(phone));
        }

        @Test
        @DisplayName("category-sourced revoke")
        void categorySourcedRevoked() {
            consentRepo.setConsent(
                "user-1", "User", "email", "marketing", false,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "User", "email", "marketing", true
            );
            // Category-sourced grant
            consentRepo.setConsent(
                "user-1", "User", "phone", "marketing", true,
                CaffeineConsentRepository.SOURCE_CATEGORY
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone));

            assertEquals(2, response.granted().size());

            assertEquals("FIELD", response.details().get(email).resolvedBy());
            assertEquals("CATEGORY", response.details().get(phone).resolvedBy());
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
                "user-1", "User", "email", "marketing", true
            );

            // Now fan out a category-level REVOKE to the same field
            mappingRepo.addFieldToCategory("User", "email", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1", "marketing", false, fields
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

            // Field-level grant should survive the category revoke
            assertTrue(response.granted().contains(scope));
            assertEquals("FIELD", response.details().get(scope).resolvedBy());
        }

        @Test
        @DisplayName("mixed precedence during evaluation")
        void mixedPrecedenceDuringEvaluation() {
            // Field-level grant for email
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
            );

            // Category fan-out revoke for email + phone
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1", "marketing", false, fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone));

            // email: field-level grant (survives category revoke)
            assertTrue(response.granted().contains(email));
            assertEquals("FIELD", response.details().get(email).resolvedBy());

            // phone: category-level revoke (no field-level to override)
            assertTrue(response.denied().contains(phone));
            assertEquals("CATEGORY", response.details().get(phone).resolvedBy());
        }

        @Test
        @DisplayName("all field-sourced — category fan-out is no-op")
        void allFieldSourcedNoop() {
            // Field-level grants for both
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "User", "phone", "marketing", true
            );

            // Category fan-out revoke — should be completely skipped
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "phone", "PII");
            Set<String> fields = mappingRepo.getFieldsForCategory("PII");
            consentRepo.setCategoryConsentForFields(
                "user-1", "marketing", false, fields
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone));

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
                "user-1", "User", "email", "marketing", true
            );

            var marketing =
                ConsentScope.to("PII", "email", "User", "marketing");
            var analytics =
                ConsentScope.to("PII", "email", "User", "analytics");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(marketing, analytics));

            assertTrue(response.granted().contains(marketing));
            assertTrue(response.denied().contains(analytics));
        }

        @Test
        @DisplayName("multiple purposes granted independently")
        void multiplePurposesGranted() {
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "analytics", true
            );

            var marketing =
                ConsentScope.to("PII", "email", "User", "marketing");
            var analytics =
                ConsentScope.to("PII", "email", "User", "analytics");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(marketing, analytics));

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
                "user-1", "User", "email", "marketing", true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            ConsentResponse r1 =
                service.evaluateConsent("user-1", Set.of(scope));
            ConsentResponse r2 =
                service.evaluateConsent("user-2", Set.of(scope));

            assertTrue(r1.granted().contains(scope));
            assertTrue(r2.denied().contains(scope));
        }

        @Test
        @DisplayName("two subjects with different consent")
        void twoSubjectsDifferentConsent() {
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-2", "User", "email", "marketing", false
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");

            ConsentResponse r1 =
                service.evaluateConsent("user-1", Set.of(scope));
            ConsentResponse r2 =
                service.evaluateConsent("user-2", Set.of(scope));

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
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "Contact", "email", "marketing", false
            );

            var userEmail =
                ConsentScope.to("PII", "email", "User", "marketing");
            var contactEmail =
                ConsentScope.to("PII", "email", "Contact", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1", Set.of(userEmail, contactEmail)
            );

            assertTrue(response.granted().contains(userEmail));
            assertTrue(response.denied().contains(contactEmail));
        }

        @Test
        @DisplayName("null entity uses _default_ sentinel")
        void nullEntityUsesSentinel() {
            consentRepo.setFieldConsent(
                "user-1", null, "email", "marketing", true
            );

            var scope = ConsentScope.to("PII", "email", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of());

            assertEquals("user-1", response.subject());
            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());
            assertTrue(response.details().isEmpty());
        }

        @Test
        @DisplayName("null request set returns empty response")
        void nullRequestSet() {
            ConsentResponse response =
                service.evaluateConsent("user-1", null);

            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName("unknown subject returns all denied")
        void unknownSubject() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("nonexistent", Set.of(scope));

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
                "user-1", "User", "email", "marketing", false
            );
            // phone has no entry

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone));

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
                "user-1", "User", "email", "marketing", true, future
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "User", "email", "marketing", true, past
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

            // Entry should not have been written (past expiry skipped)
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("null expiry means no expiration")
        void nullExpiryNoExpiration() {
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true, null
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "marketing", true, past, fields
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                    "user-1", "Entity", field, "purpose", i % 2 == 0
                );
                scopes.add(
                    ConsentScope.to("CAT", field, "Entity", "purpose")
                );
            }

            ConsentResponse response =
                service.evaluateConsent("user-1", scopes);

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
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "User", "phone", "marketing", false
            );

            var email = ConsentScope.to("PII", "email", "User", "marketing");
            var phone = ConsentScope.to("PII", "phone", "User", "marketing");
            var ssn = ConsentScope.to("PII", "ssn", "User", "marketing");

            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(email, phone, ssn));

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
                "user-1", "User", "email", "marketing", true
            );

            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));

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
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-1", "User", "phone", "analytics", true
            );
            consentRepo.setFieldConsent(
                "user-2", "User", "email", "marketing", true
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
                "user-1", "User", "email", "marketing", true
            );
            consentRepo.setFieldConsent(
                "user-2", "User", "phone", "analytics", true
            );

            consentRepo.invalidateAll();
            consentRepo.cleanUp();

            assertEquals(0, consentRepo.estimatedSize());
        }

        @Test
        @DisplayName("exists returns false after remove")
        void existsAfterRemove() {
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
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
                mappingRepo.getCategoriesForField("User", "email")
                    .contains("PII")
            );
            assertTrue(
                mappingRepo.getFieldsForCategory("PII")
                    .contains("User/email")
            );
        }

        @Test
        @DisplayName("addFieldToCategory is idempotent")
        void addIsIdempotent() {
            mappingRepo.addFieldToCategory("User", "email", "PII");
            mappingRepo.addFieldToCategory("User", "email", "PII");

            assertEquals(1, mappingRepo.getFieldsForCategory("PII").size());
            assertEquals(
                1, mappingRepo.getCategoriesForField("User", "email").size()
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

            Set<String> categories =
                mappingRepo.getCategoriesForField("User", "email");
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

            int removed = mappingRepo.removeAllMappingsForField("User", "email");
            assertEquals(2, removed);

            assertTrue(
                mappingRepo.getCategoriesForField("User", "email").isEmpty()
            );
            assertFalse(
                mappingRepo.getFieldsForCategory("PII").contains("User/email")
            );
            assertFalse(
                mappingRepo.getFieldsForCategory("CONTACT")
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
                mappingRepo.getCategoriesForField("User", "email")
                    .contains("PII")
            );
        }

        @Test
        @DisplayName("null entity uses _default_ sentinel in qualified name")
        void nullEntitySentinel() {
            mappingRepo.addFieldToCategory(null, "email", "PII");

            assertTrue(
                mappingRepo.getFieldsForCategory("PII")
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
            assertThrows(
                IllegalArgumentException.class,
                () -> new CaffeineConsentDecisionServiceConfig(0)
            );
        }

        @Test
        @DisplayName("negative maxSize throws IllegalArgumentException")
        void negativeMaxSizeThrows() {
            assertThrows(
                IllegalArgumentException.class,
                () -> new CaffeineConsentDecisionServiceConfig(-1)
            );
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
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.denied().contains(scope));
        }

        @Test
        @DisplayName("isolation check 2 — writes don't leak to next test")
        void emptyRun2() {
            consentRepo.setFieldConsent(
                "user-1", "User", "email", "marketing", true
            );
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.granted().contains(scope));
        }

        @Test
        @DisplayName("isolation check 3 — previous test's data is gone")
        void emptyRun3() {
            var scope = ConsentScope.to("PII", "email", "User", "marketing");
            ConsentResponse response =
                service.evaluateConsent("user-1", Set.of(scope));
            assertTrue(response.denied().contains(scope));
        }
    }
}
