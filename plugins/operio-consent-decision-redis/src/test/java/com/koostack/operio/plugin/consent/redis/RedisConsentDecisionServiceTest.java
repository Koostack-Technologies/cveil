package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.exception.OperioConfigurationException;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *
 * <p>The underlying schema is flat key-per-field:
 * {@code operio:consent:{subject}:{field}:{purpose}} → {@code STATUS|SOURCE}.
 * Evaluation uses a single {@code MGET} round trip.
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
        @DisplayName(
            "fieldCategoryMappingRepository() is accessible after configure()"
        )
        void mappingRepoAccessible() {
            assertNotNull(service.fieldCategoryMappingRepository());
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
        }

        @Test
        @DisplayName("single field REVOKED")
        void singleFieldRevoked() {
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
                RedisConsentDecisionService.REASON_NO_RECORD,
                response.reasons().get("ssn")
            );

            DecisionDetail detail = response.details().get(scope);
            assertNull(detail.effectiveStatus());
            assertEquals(DecisionDetail.RESOLVED_BY_NONE, detail.resolvedBy());
        }

        @Test
        @DisplayName("multiple fields — mixed outcomes")
        void multipleFieldsMixed() {
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
            consentRepo.setConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
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
        }

        @Test
        @DisplayName("fan-out covers multiple fields")
        void fanOutCoversMultipleFields() {
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
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
        }

        @Test
        @DisplayName("category-sourced REVOKED denies the field")
        void categorySourcedRevoked() {
            consentRepo.setConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                false,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
        }

        @Test
        @DisplayName("mixed field-sourced and category-sourced entries")
        void mixedSources() {
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true
            );
            consentRepo.setConsent(
                "user-1",
                null,
                "email",
                "marketing",
                true,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
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
            // Explicit field-level GRANT for ssn
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true
            );

            // Category fan-out tries to REVOKE across ssn + email + phone
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
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
        }

        @Test
        @DisplayName(
            "category fan-out overwrites prior CATEGORY entry but not FIELD entry"
        )
        void mixedPrecedenceDuringEvaluation() {
            // ssn: explicit field-level REVOKE
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                false
            );
            // email: category-sourced REVOKE (will be overwritten)
            consentRepo.setConsent(
                "user-1",
                null,
                "email",
                "marketing",
                false,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );
            // phone: no prior entry

            // Category fan-out GRANTs all three fields
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
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
        }

        @Test
        @DisplayName("all fields FIELD-sourced — category fan-out is a no-op")
        void allFieldSourcedNoop() {
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
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn", "_default_/email")
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
        }

        @Test
        @DisplayName("same field, multiple purposes all granted independently")
        void multiplePurposesGranted() {
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
        }

        @Test
        @DisplayName(
            "two subjects can have different consent for the same scope"
        )
        void twoSubjectsDifferentConsent() {
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
            assertTrue(response.details().isEmpty());
        }

        @Test
        @DisplayName("null request set returns empty response")
        void nullRequestSet() {
            ConsentResponse response = service.evaluateConsent("user-1", null);

            assertEquals("user-1", response.subject());
            assertTrue(response.granted().isEmpty());
            assertTrue(response.denied().isEmpty());
            assertTrue(response.reasons().isEmpty());
            assertTrue(response.details().isEmpty());
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
                RedisConsentDecisionService.REASON_NO_RECORD,
                response.reasons().get("ssn")
            );
        }

        @Test
        @DisplayName(
            "reason message distinguishes no-record from explicit revocation"
        )
        void reasonMessages() {
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
        }
    }

    // -----------------------------------------------------------------------
    // evaluateConsent — TTL / expiry
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateConsent — TTL / expiry")
    class TtlExpiry {

        @Test
        @DisplayName("entry with future expiresAt is evaluated normally")
        void futureExpiryGranted() {
            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                expiresAt
            );

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            assertEquals(Set.of(scope), response.granted());

            // expiresAt is null in the detail — Redis manages TTL natively
            assertNull(response.details().get(scope).expiresAt());
        }

        @Test
        @DisplayName(
            "entry with past expiresAt was never written — treated as no record"
        )
        void pastExpiryNotWritten() {
            Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                past
            );

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            ConsentResponse response = service.evaluateConsent(
                "user-1",
                Set.of(scope)
            );

            // Key was never written because TTL was non-positive
            assertTrue(response.granted().isEmpty());
            assertEquals(Set.of(scope), response.denied());
            assertEquals(
                RedisConsentDecisionService.REASON_NO_RECORD,
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
            Set<String> catFields = new java.util.HashSet<>();
            for (int i = 50; i < 100; i++) {
                catFields.add("_default_/field_" + i);
            }
            consentRepo.setCategoryConsentForFields(
                "user-1",
                "analytics",
                true,
                catFields
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
        }

        @Test
        @DisplayName("detail scope field matches the original request scope")
        void detailScopeMatchesRequest() {
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
            assertFalse(consentRepo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            consentRepo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true
            );
            assertTrue(consentRepo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            assertFalse(consentRepo.exists("user-1", null, "ssn", "marketing"));
        }
    }
}
