package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.JedisPooled;

/**
 * End-to-end integration tests for {@link RedisConsentDecisionService},
 * exercising {@code evaluateConsent} through the full service → repository
 * → Redis stack.
 *
 * <p>Uses an embedded {@link RedisServer} (jedis-mock) — no Docker required.
 * Each test starts with a freshly flushed database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RedisConsentDecisionService")
class RedisConsentDecisionServiceTest {

    private RedisServer redisServer;
    private RedisConsentDecisionService service;
    private ConsentPolicyRedisRepository consentRepo;
    private JedisPooled jedis;

    @BeforeAll
    void setUpService() throws IOException {
        redisServer = RedisServer.newRedisServer().start();
        var config = new RedisConsentDecisionServiceConfig(
            redisServer.getHost(),
            redisServer.getBindPort()
        );
        service = new RedisConsentDecisionService();
        service.configure(config);

        consentRepo = service.consentPolicyRepository();
        jedis = service.jedis();
    }

    @AfterAll
    void tearDown() throws IOException {
        if (jedis != null) {
            jedis.close();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void flushRedis() {
        jedis.flushAll();
    }

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Service lifecycle")
    class ServiceLifecycle {

        @Test
        @DisplayName("getName returns 'redis'")
        void nameIsRedis() {
            assertEquals("redis", service.getName());
        }

        @Test
        @DisplayName("getPriority returns 200")
        void priorityIs200() {
            assertEquals(200, service.getPriority());
        }

        @Test
        @DisplayName(
            "getConfigurationType returns RedisConsentDecisionServiceConfig.class"
        )
        void configurationType() {
            assertEquals(
                RedisConsentDecisionServiceConfig.class,
                service.getConfigurationType()
            );
        }

        @Test
        @DisplayName("unconfigured service throws IllegalStateException")
        void unconfiguredServiceThrows() {
            var unconfigured = new RedisConsentDecisionService();
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            assertThrows(IllegalStateException.class, () ->
                unconfigured.evaluateConsent("user-1", Set.of(scope))
            );
        }

        @Test
        @DisplayName(
            "consentPolicyRepository() is accessible after configure()"
        )
        void repoAccessible() {
            assertNotNull(service.consentPolicyRepository());
        }

        @Test
        @DisplayName("jedis() is accessible after configure()")
        void jedisAccessible() {
            assertNotNull(service.jedis());
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — field-level only
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — field-level")
    class FieldLevel {

        @Test
        @DisplayName("single field GRANTED")
        void singleFieldGranted() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertEquals("user-1", response.subject());
            assertEquals(Set.of(scope), response.granted());
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());
        }

        @Test
        @DisplayName("single field REVOKED")
        void singleFieldRevoked() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
            assertTrue(response.reasons().containsKey("ssn"));
        }

        @Test
        @DisplayName("single field — no record")
        void singleFieldNoRecord() {
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
            assertEquals(
                "No consent record found",
                response.reasons().get("ssn")
            );
        }

        @Test
        @DisplayName("multiple fields — mixed outcomes")
        void multipleFieldsMixed() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);
            consentRepo.setFieldConsent("user-1", "email", "marketing", false);
            // phone has no record

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "marketing");
            ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(ssn, email, phone)
            );

            assertEquals(Set.of(ssn), response.granted());
            assertEquals(Set.of(email, phone), response.denied());
            assertTrue(response.reasons().containsKey("email"));
            assertTrue(response.reasons().containsKey("phone"));
            assertFalse(response.reasons().containsKey("ssn"));
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — category-level fallback
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — category-level fallback")
    class CategoryFallback {

        @Test
        @DisplayName("category GRANTED covers field with no direct consent")
        void categoryGrantCoversField() {
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertEquals(Set.of(scope), response.granted());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName("category GRANTED covers multiple fields in same category")
        void categoryGrantCoversMultipleFields() {
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "marketing");
            ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(ssn, email, phone)
            );

            assertEquals(Set.of(ssn, email, phone), response.granted());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName(
            "field-level GRANTED takes priority over category-level REVOKED"
        )
        void fieldGrantOverridesCategoryRevoke() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertEquals(Set.of(scope), response.granted());
            assertTrue(response.denied().isEmpty());
        }

        @Test
        @DisplayName(
            "field-level REVOKED takes precedence over category-level GRANTED"
        )
        void fieldRevokedTakesPrecedenceOverCategoryGrant() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", false);
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            // Most-specific-wins: field-level REVOKED cannot be overridden
            // by a broader category-level GRANTED
            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
        }

        @Test
        @DisplayName("category REVOKED denies when no field-level exists")
        void categoryRevokedDenies() {
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
        }

        @Test
        @DisplayName("different categories evaluated independently")
        void differentCategoriesIndependent() {
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);
            consentRepo.setCategoryConsent(
                "user-1",
                "Financial",
                "marketing",
                false
            );

            ConsentScope piiField = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope finField = ConsentScope.to(
                "Financial",
                "account_no",
                "marketing"
            );

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(piiField, finField)
            );

            assertEquals(Set.of(piiField), response.granted());
            assertEquals(Set.of(finField), response.denied());
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — purpose isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — purpose isolation")
    class PurposeIsolation {

        @Test
        @DisplayName(
            "same field, different purposes are evaluated independently"
        )
        void fieldPurposeIsolation() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);
            // ssn for analytics has no consent

            ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertEquals(Set.of(marketing), response.granted());
            assertEquals(Set.of(analytics), response.denied());
        }

        @Test
        @DisplayName(
            "same category, different purposes are evaluated independently"
        )
        void categoryPurposeIsolation() {
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);
            // PII for analytics has no consent

            ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertEquals(Set.of(marketing), response.granted());
            assertEquals(Set.of(analytics), response.denied());
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — subject isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — subject isolation")
    class SubjectIsolation {

        @Test
        @DisplayName("consent for one subject does not affect another")
        void subjectIsolation() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            ConsentResponse r1 = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = service.evaluateConsent(
                "user-2",
                Set.of(scope)
            );

            assertEquals("user-1", r1.subject());
            assertEquals(Set.of(scope), r1.granted());

            assertEquals("user-2", r2.subject());
            assertTrue(r2.granted().isEmpty());
            assertEquals(Set.of(scope), r2.denied());
        }

        @Test
        @DisplayName(
            "two subjects can have different consent for the same scope"
        )
        void twoSubjectsDifferentConsent() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);
            consentRepo.setFieldConsent("user-2", "ssn", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            ConsentResponse r1 = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );
            ConsentResponse r2 = service.evaluateConsent(
                "user-2",
                Set.of(scope)
            );

            assertEquals(Set.of(scope), r1.granted());
            assertEquals(Set.of(scope), r2.denied());
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — edge cases")
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
        }

        @Test
        @DisplayName("unknown subject — all scopes denied")
        void unknownSubject() {
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "unknown-user",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
            assertEquals(
                "No consent record found",
                response.reasons().get("ssn")
            );
        }

        @Test
        @DisplayName(
            "reason message distinguishes no-record from explicit revocation"
        )
        void reasonMessages() {
            consentRepo.setFieldConsent("user-1", "email", "marketing", false);
            // ssn has no record at all

            ConsentScope email = ConsentScope.to("PII", "email", "marketing");
            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(email, ssn)
            );

            assertEquals(
                "Consent status is REVOKED",
                response.reasons().get("email")
            );
            assertEquals(
                "No consent record found",
                response.reasons().get("ssn")
            );
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — large batch
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — large batch")
    class LargeBatch {

        @Test
        @DisplayName("evaluates 100 scopes in a single call")
        void hundredScopes() {
            // Grant category-level consent for PII:marketing
            consentRepo.setCategoryConsent("user-1", "PII", "marketing", true);

            // Grant field-level consent for 50 fields under Financial:analytics
            for (int i = 0; i < 50; i++) {
                consentRepo.setFieldConsent(
                    "user-1",
                    "field_" + i,
                    "analytics",
                    true
                );
            }

            // Build 100 scopes: 50 PII/marketing (category-granted),
            // 50 Financial/analytics (field-granted)
            Set<ConsentScope> scopes = new java.util.HashSet<>();
            for (int i = 0; i < 50; i++) {
                scopes.add(
                    ConsentScope.to("PII", "pii_field_" + i, "marketing")
                );
                scopes.add(
                    ConsentScope.to("Financial", "field_" + i, "analytics")
                );
            }

            ConsentResponse response = service.evaluateConsent(
                "user-1",
                scopes
            );

            assertEquals(100, response.granted().size());
            assertTrue(response.denied().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Test isolation verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Test isolation")
    class TestIsolationCheck {

        @Test
        @DisplayName("Redis is empty at test start — run 1")
        void emptyRun1() {
            assertFalse(consentRepo.exists("user-1"));
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            consentRepo.setFieldConsent("user-1", "ssn", "marketing", true);
            assertTrue(consentRepo.exists("user-1"));
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            assertFalse(consentRepo.exists("user-1"));
        }
    }
}
