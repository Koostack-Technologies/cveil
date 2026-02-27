package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.exception.OperioConfigurationException;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * End-to-end integration tests for {@link RedisConsentDecisionService},
 * exercising {@code evaluateConsent} through the full service → repository
 * → Redis stack.
 *
 * <p>Uses an embedded {@link RedisServer} (jedis-mock) — no Docker required.
 * Each test starts with a freshly flushed database.
 *
 * <p>The underlying schema is Hash-per-subject:
 * {@code {tenant}:consent:{subject}} → Hash of {@code c:{cat}:{purpose}} and
 * {@code f:{entity/field}:{purpose}} fields. Evaluation uses a single
 * {@code HMGET} round trip with field-over-category precedence.
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
        @DisplayName("unconfigured service throws OperioConfigurationException")
        void unconfiguredServiceThrows() {
            var unconfigured = new RedisConsentDecisionService();
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            assertThrows(OperioConfigurationException.class, () ->
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
    // evaluateConsent — field-level
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — field-level")
    class FieldLevel {

        @Test
        @DisplayName("single field GRANTED")
        void singleFieldGranted() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertEquals("user-1", response.subject());
                assertEquals(Set.of(scope), response.granted());
                assertTrue(response.denied().isEmpty());
                assertTrue(response.reasons().isEmpty());

                // Structured detail
                DecisionDetail detail = response.details().get(scope);
                assertNotNull(detail);
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    detail.effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    detail.resolvedBy()
                );
                assertNull(detail.expiresAt());
            });
        }

        @Test
        @DisplayName("single field REVOKED")
        void singleFieldRevoked() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    false
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertTrue(response.granted().isEmpty());
                assertEquals(Set.of(scope), response.denied());
                assertEquals(
                    RedisConsentDecisionService.REASON_REVOKED,
                    response.reasons().get("ssn")
                );

                DecisionDetail detail = response.details().get(scope);
                assertEquals(
                    ConsentPolicyRedisRepository.REVOKED,
                    detail.effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    detail.resolvedBy()
                );
            });
        }

        @Test
        @DisplayName("single field — no record")
        void singleFieldNoRecord() {
            TenantContext.runWhere("_default_", () -> {
                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertTrue(response.granted().isEmpty());
                assertEquals(Set.of(scope), response.denied());
                assertEquals(
                    RedisConsentDecisionService.REASON_NO_RECORD,
                    response.reasons().get("ssn")
                );

                DecisionDetail detail = response.details().get(scope);
                assertNull(detail.effectiveStatus());
                assertEquals(DecisionDetail.RESOLVED_BY_NONE, detail.resolvedBy());
            });
        }

        @Test
        @DisplayName("multiple fields — mixed outcomes")
        void multipleFieldsMixed() {
            TenantContext.runWhere("_default_", () -> {
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
                    false
                );
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

                // All three have detail entries
                assertEquals(3, response.details().size());
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — category-sourced entries
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — category-sourced entries")
    class CategorySourced {

        @Test
        @DisplayName("category-sourced GRANTED is treated as granted")
        void categorySourcedGranted() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    true
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertEquals(Set.of(scope), response.granted());
                assertTrue(response.denied().isEmpty());

                DecisionDetail detail = response.details().get(scope);
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    detail.effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    detail.resolvedBy()
                );
            });
        }

        @Test
        @DisplayName("fan-out covers multiple fields")
        void fanOutCoversMultipleFields() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    true
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");
                ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, phone)
                );

                assertEquals(Set.of(ssn, email, phone), response.granted());
                assertTrue(response.denied().isEmpty());

                for (ConsentScope scope : Set.of(ssn, email, phone)) {
                    assertEquals(
                        ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                        response.details().get(scope).resolvedBy()
                    );
                }
            });
        }

        @Test
        @DisplayName("category-sourced REVOKED denies the field")
        void categorySourcedRevoked() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    false
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertTrue(response.granted().isEmpty());
                assertEquals(Set.of(scope), response.denied());
            });
        }

        @Test
        @DisplayName("mixed field-sourced and category-sourced entries")
        void mixedSources() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    true
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );

                assertEquals(Set.of(ssn, email), response.granted());

                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(ssn).resolvedBy()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    response.details().get(email).resolvedBy()
                );
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — category fan-out precedence
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — category fan-out precedence")
    class CategoryFanOutPrecedence {

        @Test
        @DisplayName(
            "field-level entry survives category fan-out during evaluation"
        )
        void fieldLevelSurvivesCategoryFanOut() {
            TenantContext.runWhere("_default_", () -> {
                // Explicit field-level GRANT for ssn
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );

                // Category fan-out tries to REVOKE across ssn + email + phone
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    false
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");
                ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, phone)
                );

                // ssn was FIELD-sourced GRANTED — must survive the category REVOKE
                assertEquals(Set.of(ssn), response.granted());
                assertTrue(response.denied().contains(email));
                assertTrue(response.denied().contains(phone));

                // ssn detail: still FIELD-sourced
                DecisionDetail ssnDetail = response.details().get(ssn);
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    ssnDetail.effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    ssnDetail.resolvedBy()
                );

                // email detail: CATEGORY-sourced REVOKED
                DecisionDetail emailDetail = response.details().get(email);
                assertEquals(
                    ConsentPolicyRedisRepository.REVOKED,
                    emailDetail.effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    emailDetail.resolvedBy()
                );
            });
        }

        @Test
        @DisplayName(
            "category fan-out overwrites prior CATEGORY entry but not FIELD entry"
        )
        void mixedPrecedenceDuringEvaluation() {
            TenantContext.runWhere("_default_", () -> {
                // ssn: explicit field-level REVOKE
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    false
                );
                // email: category-sourced REVOKE (will be overwritten)
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    false
                );
                // phone: no prior entry

                // Category fan-out GRANTs all three fields
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    true
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");
                ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, phone)
                );

                // ssn: still REVOKED|FIELD — denied
                assertTrue(response.denied().contains(ssn));
                assertEquals(
                    ConsentPolicyRedisRepository.REVOKED,
                    response.details().get(ssn).effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(ssn).resolvedBy()
                );

                // email: overwritten to GRANTED|CATEGORY — granted
                assertTrue(response.granted().contains(email));
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    response.details().get(email).effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    response.details().get(email).resolvedBy()
                );

                // phone: new GRANTED|CATEGORY — granted
                assertTrue(response.granted().contains(phone));
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    response.details().get(phone).effectiveStatus()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    response.details().get(phone).resolvedBy()
                );
            });
        }

        @Test
        @DisplayName("all fields FIELD-sourced — category fan-out is a no-op")
        void allFieldSourcedNoop() {
            TenantContext.runWhere("_default_", () -> {
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
                    false
                );

                // Attempt category REVOKE on both
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "marketing",
                    false
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email)
                );

                // ssn still GRANTED|FIELD
                assertTrue(response.granted().contains(ssn));
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(ssn).resolvedBy()
                );

                // email still REVOKED|FIELD
                assertTrue(response.denied().contains(email));
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(email).resolvedBy()
                );
            });
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
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                // ssn for analytics has no consent

                ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(marketing, analytics)
                );

                assertEquals(Set.of(marketing), response.granted());
                assertEquals(Set.of(analytics), response.denied());
            });
        }

        @Test
        @DisplayName("same field, multiple purposes all granted independently")
        void multiplePurposesGranted() {
            TenantContext.runWhere("_default_", () -> {
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
                    "ssn",
                    "analytics",
                    true
                );

                ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(marketing, analytics)
                );

                assertEquals(Set.of(marketing, analytics), response.granted());
                assertTrue(response.denied().isEmpty());
            });
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
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );

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
            });
        }

        @Test
        @DisplayName(
            "two subjects can have different consent for the same scope"
        )
        void twoSubjectsDifferentConsent() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setFieldConsent(
                    "user-2",
                    null,
                    "ssn",
                    "marketing",
                    false
                );

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
            });
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
            TenantContext.runWhere("_default_", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of()
                );

                assertEquals("user-1", response.subject());
                assertTrue(response.granted().isEmpty());
                assertTrue(response.denied().isEmpty());
                assertTrue(response.reasons().isEmpty());
                assertTrue(response.details().isEmpty());
            });
        }

        @Test
        @DisplayName("null request set returns empty response")
        void nullRequestSet() {
            TenantContext.runWhere("_default_", () -> {
                ConsentResponse response = service.evaluateConsent("user-1", null);

                assertEquals("user-1", response.subject());
                assertTrue(response.granted().isEmpty());
                assertTrue(response.denied().isEmpty());
                assertTrue(response.reasons().isEmpty());
                assertTrue(response.details().isEmpty());
            });
        }

        @Test
        @DisplayName("unknown subject — all scopes denied")
        void unknownSubject() {
            TenantContext.runWhere("_default_", () -> {
                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "unknown-user",
                    Set.of(scope)
                );

                assertTrue(response.granted().isEmpty());
                assertEquals(Set.of(scope), response.denied());
                assertEquals(
                    RedisConsentDecisionService.REASON_NO_RECORD,
                    response.reasons().get("ssn")
                );
            });
        }

        @Test
        @DisplayName(
            "reason message distinguishes no-record from explicit revocation"
        )
        void reasonMessages() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "marketing",
                    false
                );
                // ssn has no record at all

                ConsentScope email = ConsentScope.to("PII", "email", "marketing");
                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(email, ssn)
                );

                assertEquals(
                    RedisConsentDecisionService.REASON_REVOKED,
                    response.reasons().get("email")
                );
                assertEquals(
                    RedisConsentDecisionService.REASON_NO_RECORD,
                    response.reasons().get("ssn")
                );
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — TTL / expiry
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — TTL / expiry")
    class TtlExpiry {

        @Test
        @DisplayName("granted field has null expiresAt (Hash schema has no per-field TTL)")
        void grantedFieldHasNullExpiresAt() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                assertEquals(Set.of(scope), response.granted());

                // expiresAt is always null — Hash schema has no per-field TTL
                assertNull(response.details().get(scope).expiresAt());
            });
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
            TenantContext.runWhere("_default_", () -> {
                // Grant field-level consent for 50 fields
                for (int i = 0; i < 50; i++) {
                    consentRepo.setFieldConsent(
                        "user-1",
                        null,
                        "field_" + i,
                        "marketing",
                        true
                    );
                }

                // Grant category-sourced consent for another 50 fields
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "analytics",
                    true
                );

                // Build 100 scopes
                Set<ConsentScope> scopes = new java.util.HashSet<>();
                for (int i = 0; i < 50; i++) {
                    scopes.add(ConsentScope.to("PII", "field_" + i, "marketing"));
                }
                for (int i = 50; i < 100; i++) {
                    scopes.add(ConsentScope.to("PII", "field_" + i, "analytics"));
                }

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    scopes
                );

                assertEquals(100, response.granted().size());
                assertTrue(response.denied().isEmpty());
                assertEquals(100, response.details().size());
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — details map completeness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — details map")
    class DetailsMap {

        @Test
        @DisplayName("details map contains an entry for every requested scope")
        void detailsForAllScopes() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                // email is revoked, phone has no record

                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "marketing",
                    false
                );

                ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
                ConsentScope email = ConsentScope.to("PII", "email", "marketing");
                ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, phone)
                );

                assertEquals(3, response.details().size());
                assertNotNull(response.details().get(ssn));
                assertNotNull(response.details().get(email));
                assertNotNull(response.details().get(phone));

                // Verify resolvedBy values
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(ssn).resolvedBy()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_FIELD,
                    response.details().get(email).resolvedBy()
                );
                assertEquals(
                    DecisionDetail.RESOLVED_BY_NONE,
                    response.details().get(phone).resolvedBy()
                );
            });
        }

        @Test
        @DisplayName("detail scope field matches the original request scope")
        void detailScopeMatchesRequest() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );

                ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );

                DecisionDetail detail = response.details().get(scope);
                assertEquals(scope, detail.scope());
            });
        }
    }

    // -----------------------------------------------------------------------
    // Test isolation verification
    // -----------------------------------------------------------------------

    // =======================================================================
    // Tenant-Aware Service Tests (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Tenant-Aware Service Evaluation")
    class TenantAwareServiceEvaluation {

        @Test
        @DisplayName(
            "evaluateConsent in tenant A returns GRANTED, tenant B returns DENIED"
        )
        void evaluateConsentTenantIsolation() throws Exception {
            ConsentScope scope = new ConsentScope(
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

            // Service evaluation in "acme" — GRANTED
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(
                    response.granted().contains(scope),
                    "Tenant acme should see GRANTED"
                );
                assertFalse(response.denied().contains(scope));
                return null;
            });

            // Service evaluation in "globex" — DENIED (no record)
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertFalse(
                    response.granted().contains(scope),
                    "Tenant globex should NOT see acme's grant"
                );
                assertTrue(response.denied().contains(scope));
                assertEquals(
                    "No consent record found",
                    response.reasons().get("ssn")
                );
                return null;
            });

            // Service evaluation with no tenant context — throws (TenantContext required)
            assertThrows(OperioConfigurationException.class, () ->
                service.evaluateConsent("user-1", Set.of(scope))
            );
        }

        @Test
        @DisplayName("two tenants with opposing decisions each see their own")
        void crossTenantOpposingDecisions() throws Exception {
            ConsentScope scope = new ConsentScope(
                null,
                "email",
                null,
                "analytics"
            );

            // Tenant "acme" grants
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "analytics",
                    true
                );
                return null;
            });

            // Tenant "globex" revokes same scope
            TenantContext.callWhere("globex", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "analytics",
                    false
                );
                return null;
            });

            // Acme → GRANTED
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(response.granted().contains(scope));
                assertFalse(response.denied().contains(scope));
                return null;
            });

            // Globex → DENIED (explicitly revoked)
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertFalse(response.granted().contains(scope));
                assertTrue(response.denied().contains(scope));
                assertEquals(
                    "Consent explicitly revoked",
                    response.reasons().get("email")
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "details map is tenant-scoped — source tag only visible in owning tenant"
        )
        void detailsMapTenantScoped() throws Exception {
            ConsentScope scope = new ConsentScope(
                null,
                "dob",
                null,
                "marketing"
            );

            // Grant at field level in "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "dob",
                    "marketing",
                    true
                );
                return null;
            });

            // Details in "acme" — has source FIELD
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                DecisionDetail detail = response.details().get(scope);
                assertNotNull(detail);
                assertEquals("GRANTED", detail.effectiveStatus());
                assertEquals("FIELD", detail.resolvedBy());
                return null;
            });

            // Details in "globex" — no record, status is null
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                DecisionDetail detail = response.details().get(scope);
                assertNotNull(
                    detail,
                    "Detail should exist even for missing scope"
                );
                assertNull(detail.effectiveStatus());
                return null;
            });
        }

        @Test
        @DisplayName(
            "batch evaluation across multiple scopes respects tenant isolation"
        )
        void batchEvaluationTenantIsolation() throws Exception {
            ConsentScope ssn = new ConsentScope(null, "ssn", null, "marketing");
            ConsentScope email = new ConsentScope(
                null,
                "email",
                null,
                "marketing"
            );
            ConsentScope dob = new ConsentScope(null, "dob", null, "marketing");

            // Tenant "acme": grant ssn and email
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

            // Tenant "globex": grant only dob
            TenantContext.callWhere("globex", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "dob",
                    "marketing",
                    true
                );
                return null;
            });

            // Acme evaluates all three — ssn and email granted, dob denied
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, dob)
                );
                assertTrue(response.granted().contains(ssn));
                assertTrue(response.granted().contains(email));
                assertTrue(response.denied().contains(dob));
                assertEquals(
                    3,
                    response.granted().size() + response.denied().size()
                );
                return null;
            });

            // Globex evaluates all three — only dob granted
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssn, email, dob)
                );
                assertTrue(response.denied().contains(ssn));
                assertTrue(response.denied().contains(email));
                assertTrue(response.granted().contains(dob));
                return null;
            });
        }

        @Test
        @DisplayName(
            "empty request set returns empty response regardless of tenant"
        )
        void emptyRequestTenantScoped() throws Exception {
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of()
                );
                assertTrue(response.granted().isEmpty());
                assertTrue(response.denied().isEmpty());
                assertTrue(response.reasons().isEmpty());
                return null;
            });
        }

        @Test
        @DisplayName(
            "category fan-out via repo is visible through service only in correct tenant"
        )
        void categoryFanOutServiceTenantIsolation() throws Exception {
            ConsentScope ssnScope = new ConsentScope(
                "PII",
                "ssn",
                null,
                "analytics"
            );
            ConsentScope emailScope = new ConsentScope(
                "PII",
                "email",
                null,
                "analytics"
            );

            // Set category consent in "acme"
            TenantContext.callWhere("acme", () -> {
                consentRepo.setCategoryConsent(
                    "user-1",
                    "PII",
                    "analytics",
                    true
                );
                return null;
            });

            // Service evaluation in "acme" — both granted
            TenantContext.callWhere("acme", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(response.granted().contains(ssnScope));
                assertTrue(response.granted().contains(emailScope));
                return null;
            });

            // Service evaluation in "globex" — both denied
            TenantContext.callWhere("globex", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-1",
                    Set.of(ssnScope, emailScope)
                );
                assertTrue(response.denied().contains(ssnScope));
                assertTrue(response.denied().contains(emailScope));
                return null;
            });
        }

        @Test
        @DisplayName("subject isolation holds within a tenant context")
        void subjectIsolationWithinTenant() throws Exception {
            ConsentScope scope = new ConsentScope(
                null,
                "ssn",
                null,
                "marketing"
            );

            // Grant for user-1 in "acme"
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

            // user-1 in "acme" is granted
            TenantContext.callWhere("acme", () -> {
                ConsentResponse r1 = service.evaluateConsent(
                    "user-1",
                    Set.of(scope)
                );
                assertTrue(r1.granted().contains(scope));

                // user-2 in "acme" is denied (no record)
                ConsentResponse r2 = service.evaluateConsent(
                    "user-2",
                    Set.of(scope)
                );
                assertTrue(r2.denied().contains(scope));
                return null;
            });
        }
    }

    // =======================================================================
    // Redis Key Pattern / SCAN Verification (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Redis Key Patterns & SCAN")
    class RedisKeyPatternsAndScan {

        @Test
        @DisplayName("SCAN with tenant prefix finds only that tenant's keys")
        void scanWithTenantPrefixIsolation() throws Exception {
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

            // Also write in a separate tenant context
            TenantContext.callWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "dob",
                    "marketing",
                    true
                );
                return null;
            });

            // SCAN for acme keys (consent hash + reverse index keys)
            Set<String> acmeKeys = scanKeys("acme:*");
            assertTrue(
                acmeKeys.size() >= 1,
                "Expected at least 1 key for tenant acme, got: " + acmeKeys
            );
            for (String key : acmeKeys) {
                assertTrue(
                    key.startsWith("acme:"),
                    "All acme keys should start with 'acme:' — got: " +
                        key
                );
            }

            // SCAN for globex keys
            Set<String> globexKeys = scanKeys("globex:*");
            assertTrue(
                globexKeys.size() >= 1,
                "Expected at least 1 key for tenant globex, got: " + globexKeys
            );

            // SCAN for _default_ tenant keys
            // These should NOT include other tenant-prefixed keys
            Set<String> defaultKeys = scanKeys("_default_:*");
            assertTrue(
                defaultKeys.size() >= 1,
                "Expected at least 1 key for _default_ tenant, got: " + defaultKeys
            );
            for (String key : defaultKeys) {
                assertFalse(
                    key.contains("acme:") || key.contains("globex:"),
                    "Default keys should not contain other tenant segments: " + key
                );
            }
        }

        @Test
        @DisplayName("removeAllConsent SCAN pattern includes tenant prefix")
        void removeAllConsentScanPatternIncludesTenantPrefix()
            throws Exception {
            // Populate data in two tenants and default namespace
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
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "dob",
                    "analytics",
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

            TenantContext.callWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "phone",
                    "marketing",
                    true
                );
                return null;
            });

            // Remove all for user-1 in "acme"
            TenantContext.callWhere("acme", () -> {
                long removed = consentRepo.deleteAllCachedKeysForSubject("user-1");
                assertEquals(3, removed, "Should remove exactly 3 acme entries");
                return null;
            });

            // Acme's data is gone
            TenantContext.callWhere("acme", () -> {
                assertNull(
                    consentRepo.evaluate("user-1", null, null, "ssn", "marketing").effectiveStatus()
                );
                assertNull(
                    consentRepo.evaluate("user-1", null, null, "email", "marketing").effectiveStatus()
                );
                assertNull(
                    consentRepo.evaluate("user-1", null, null, "dob", "analytics").effectiveStatus()
                );
                return null;
            });

            // Globex is untouched
            TenantContext.callWhere("globex", () -> {
                assertNotNull(
                    consentRepo.evaluate("user-1", null, null, "ssn", "marketing").effectiveStatus()
                );
                return null;
            });

            // _default_ tenant is untouched
            TenantContext.callWhere("_default_", () -> {
                assertNotNull(
                    consentRepo.evaluate("user-1", null, null, "phone", "marketing").effectiveStatus()
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "Redis keys for different tenants occupy distinct key namespaces"
        )
        void tenantKeyNamespaceSeparation() throws Exception {
            // Write identical scope in three contexts
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
            TenantContext.callWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                return null;
            });

            // Verify three distinct keys exist in Redis
            Set<String> allKeys = scanKeys("*:consent:*");
            assertEquals(
                3,
                allKeys.size(),
                "Expected 3 distinct keys across 2 tenants + _default_, got: " +
                    allKeys
            );

            // Verify each key has the expected prefix
            boolean hasAcme = allKeys
                .stream()
                .anyMatch(k -> k.startsWith("acme:"));
            boolean hasGlobex = allKeys
                .stream()
                .anyMatch(k -> k.startsWith("globex:"));
            boolean hasDefault = allKeys
                .stream()
                .anyMatch(
                    k ->
                        k.startsWith("_default_:") &&
                        !k.contains("acme:") &&
                        !k.contains("globex:")
                );

            assertTrue(hasAcme, "Should have an acme-prefixed key");
            assertTrue(hasGlobex, "Should have a globex-prefixed key");
            assertTrue(hasDefault, "Should have a _default_ tenant key");
        }

        /**
         * Helper to SCAN all keys matching a pattern from the shared jedis
         * client.
         */
        private Set<String> scanKeys(String pattern) {
            Set<String> result = new HashSet<>();
            String cursor = "0";
            ScanParams params = new ScanParams().match(pattern).count(100);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                result.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!"0".equals(cursor));
            return result;
        }
    }

    // =======================================================================
    // Redis ACL Key Pattern Verification (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Redis ACL Key Pattern Convention")
    class RedisAclKeyPatternConvention {

        @Test
        @DisplayName(
            "ACL key pattern for a tenant matches the repo's key prefix"
        )
        void aclPatternMatchesRepoPrefix() throws Exception {
            // The TenantManager provisions ACL with pattern:
            //   {tenantId}:*
            // Verify the repo's hash key aligns with this convention.

            TenantContext.callWhere("acme", () -> {
                String hashKey = consentRepo.consentHashKey("test-subject");
                assertTrue(
                    hashKey.startsWith("acme:"),
                    "Hash key '" +
                        hashKey +
                        "' should start with tenant prefix 'acme:'"
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "all keys written by a tenant match the expected ACL glob pattern"
        )
        void allTenantKeysMatchAclGlob() throws Exception {
            TenantContext.callWhere("acme", () -> {
                // Write various key types
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setFieldConsent(
                    "user-2",
                    "User",
                    "email",
                    "analytics",
                    false
                );
                consentRepo.setCategoryConsent(
                    "user-3",
                    "PII",
                    "marketing",
                    true
                );
                return null;
            });

            // Verify all keys match the ACL pattern {tenantId}:*
            String aclPattern = "acme:*";
            Set<String> allKeys = new HashSet<>();
            String cursor = "0";
            ScanParams params = new ScanParams().match(aclPattern).count(100);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                allKeys.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!"0".equals(cursor));

            // 3 consent hash keys + reverse index keys
            assertTrue(
                allKeys.size() >= 3,
                "Expected at least 3 keys for tenant acme, got: " + allKeys
            );

            for (String key : allKeys) {
                assertTrue(
                    key.startsWith("acme:"),
                    "Key '" +
                        key +
                        "' should match ACL pattern '" +
                        aclPattern +
                        "'"
                );
            }
        }

        @Test
        @DisplayName(
            "keys from one tenant do NOT match another tenant's ACL glob"
        )
        void crossTenantAclIsolation() throws Exception {
            // Write in "acme"
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

            // SCAN with "globex" pattern should find nothing
            String globexPattern = "globex:*";
            Set<String> globexKeys = new HashSet<>();
            String cursor = "0";
            ScanParams params = new ScanParams()
                .match(globexPattern)
                .count(100);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                globexKeys.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!"0".equals(cursor));

            assertTrue(
                globexKeys.isEmpty(),
                "Globex ACL pattern should NOT match acme's keys, found: " +
                    globexKeys
            );
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
            "full tenant lifecycle: create A & B → grant in A → " +
                "evaluate B denied → evaluate A granted → " +
                "delete A → A gone, B unaffected"
        )
        void fullTenantLifecycle() throws Exception {
            ConsentScope ssn = new ConsentScope(null, "ssn", null, "marketing");
            ConsentScope email = new ConsentScope(
                null,
                "email",
                null,
                "marketing"
            );

            // --- Set up tenant A: grant consent ---
            TenantContext.callWhere("tenantA", () -> {
                consentRepo.setFieldConsent(
                    "user-123",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                consentRepo.setFieldConsent(
                    "user-123",
                    null,
                    "email",
                    "marketing",
                    true
                );
                return null;
            });

            // --- Set up tenant B: separate data ---
            TenantContext.callWhere("tenantB", () -> {
                // Grant only ssn, not email
                consentRepo.setFieldConsent(
                    "user-123",
                    null,
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
                    Set.of(ssn, email)
                );
                assertTrue(
                    response.granted().contains(ssn),
                    "Tenant B should have ssn granted"
                );
                assertTrue(
                    response.denied().contains(email),
                    "Tenant B should NOT have email granted"
                );
                return null;
            });

            // --- Evaluate in A → both granted ---
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssn, email)
                );
                assertTrue(response.granted().contains(ssn));
                assertTrue(response.granted().contains(email));
                assertEquals(2, response.granted().size());
                assertTrue(response.denied().isEmpty());
                return null;
            });

            // --- "Delete" tenant A: remove all consent ---
            TenantContext.callWhere("tenantA", () -> {
                long removed = consentRepo.deleteAllCachedKeysForSubject("user-123");
                assertTrue(removed > 0, "Should remove consent entries for tenantA");
                return null;
            });

            // --- Verify tenant A data is gone ---
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssn, email)
                );
                assertTrue(
                    response.denied().contains(ssn),
                    "Tenant A ssn should be denied after delete"
                );
                assertTrue(
                    response.denied().contains(email),
                    "Tenant A email should be denied after delete"
                );
                return null;
            });

            // --- Verify tenant B is completely unaffected ---
            TenantContext.callWhere("tenantB", () -> {
                ConsentResponse response = service.evaluateConsent(
                    "user-123",
                    Set.of(ssn, email)
                );
                assertTrue(
                    response.granted().contains(ssn),
                    "Tenant B ssn should still be granted after deleting A"
                );
                assertTrue(
                    response.denied().contains(email),
                    "Tenant B email should still be denied (was never granted)"
                );
                return null;
            });
        }

        @Test
        @DisplayName("concurrent subjects across tenants remain isolated")
        void concurrentSubjectsAcrossTenants() throws Exception {
            ConsentScope scope = new ConsentScope(
                null,
                "ssn",
                null,
                "marketing"
            );

            // Three tenants, each with a "user-1" making different consent decisions
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
            TenantContext.callWhere("gamma", () -> {
                // gamma has no record for user-1
                return null;
            });

            // Each tenant sees its own decision
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
                    "beta: user-1 should be DENIED"
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
            "entity-qualified fields are isolated across tenants through service"
        )
        void entityQualifiedFieldsTenantIsolation() throws Exception {
            ConsentScope userEmail = new ConsentScope(
                null,
                "email",
                "User",
                "marketing"
            );
            ConsentScope contactEmail = new ConsentScope(
                null,
                "email",
                "Contact",
                "marketing"
            );

            // Tenant A: grant User/email
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

            // Tenant B: grant Contact/email
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

            // Tenant A sees only User/email
            TenantContext.callWhere("tenantA", () -> {
                ConsentResponse r = service.evaluateConsent(
                    "user-1",
                    Set.of(userEmail, contactEmail)
                );
                assertTrue(r.granted().contains(userEmail));
                assertTrue(r.denied().contains(contactEmail));
                return null;
            });

            // Tenant B sees only Contact/email
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
            TenantContext.runWhere("_default_", () -> {
                assertNull(consentRepo.evaluate("user-1", null, null, "ssn", "marketing").effectiveStatus());
            });
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            TenantContext.runWhere("_default_", () -> {
                consentRepo.setFieldConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing",
                    true
                );
                assertNotNull(consentRepo.evaluate("user-1", null, null, "ssn", "marketing").effectiveStatus());
            });
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            TenantContext.runWhere("_default_", () -> {
                assertNull(consentRepo.evaluate("user-1", null, null, "ssn", "marketing").effectiveStatus());
            });
        }
    }
}
