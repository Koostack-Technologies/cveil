package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 * Integration tests for {@link ConsentPolicyRedisRepository} backed by an
 * embedded {@link RedisServer} (jedis-mock) — no Docker required.
 *
 * <p>Each {@code @Nested} class groups related scenarios. {@code @BeforeEach}
 * flushes the entire Redis database so every test starts from a clean slate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConsentPolicyRedisRepository")
class ConsentPolicyRedisRepositoryTest {

    private RedisServer redisServer;
    private JedisPooled jedis;
    private ConsentPolicyRedisRepository repo;

    @BeforeAll
    void setUpServer() throws IOException {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(
            redisServer.getHost(),
            redisServer.getBindPort()
        );
        repo = new ConsentPolicyRedisRepository(jedis);
    }

    @AfterAll
    void tearDownServer() throws IOException {
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
    // Field-level consent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Field-level consent")
    class FieldLevelConsent {

        @Test
        @DisplayName("setFieldConsent stores a GRANTED status")
        void grantField() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);

            Optional<String> status = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.GRANTED, status.get());
        }

        @Test
        @DisplayName("setFieldConsent stores a REVOKED status")
        void revokeField() {
            repo.setFieldConsent("user-1", "ssn", "marketing", false);

            Optional<String> status = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.REVOKED, status.get());
        }

        @Test
        @DisplayName("setFieldConsent overwrites a previous status")
        void overwriteField() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setFieldConsent("user-1", "ssn", "marketing", false);

            Optional<String> status = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.REVOKED, status.get());
        }

        @Test
        @DisplayName("getFieldConsent returns empty when no record exists")
        void fieldMiss() {
            Optional<String> status = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            assertTrue(status.isEmpty());
        }

        @Test
        @DisplayName("removeFieldConsent deletes the entry")
        void removeField() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.removeFieldConsent("user-1", "ssn", "marketing");

            Optional<String> status = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            assertTrue(status.isEmpty());
        }

        @Test
        @DisplayName("removeFieldConsent is a no-op when entry does not exist")
        void removeFieldNoOp() {
            assertDoesNotThrow(() ->
                repo.removeFieldConsent("user-1", "ssn", "marketing")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Category-level consent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Category-level consent")
    class CategoryLevelConsent {

        @Test
        @DisplayName("setCategoryConsent stores a GRANTED status")
        void grantCategory() {
            repo.setCategoryConsent("user-1", "PII", "analytics", true);

            Optional<String> status = repo.getCategoryConsent(
                "user-1",
                "PII",
                "analytics"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.GRANTED, status.get());
        }

        @Test
        @DisplayName("setCategoryConsent stores a REVOKED status")
        void revokeCategory() {
            repo.setCategoryConsent("user-1", "PII", "analytics", false);

            Optional<String> status = repo.getCategoryConsent(
                "user-1",
                "PII",
                "analytics"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.REVOKED, status.get());
        }

        @Test
        @DisplayName("setCategoryConsent overwrites a previous status")
        void overwriteCategory() {
            repo.setCategoryConsent("user-1", "PII", "analytics", true);
            repo.setCategoryConsent("user-1", "PII", "analytics", false);

            Optional<String> status = repo.getCategoryConsent(
                "user-1",
                "PII",
                "analytics"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.REVOKED, status.get());
        }

        @Test
        @DisplayName("getCategoryConsent returns empty when no record exists")
        void categoryMiss() {
            Optional<String> status = repo.getCategoryConsent(
                "user-1",
                "PII",
                "analytics"
            );
            assertTrue(status.isEmpty());
        }

        @Test
        @DisplayName("removeCategoryConsent deletes the entry")
        void removeCategory() {
            repo.setCategoryConsent("user-1", "PII", "analytics", true);
            repo.removeCategoryConsent("user-1", "PII", "analytics");

            Optional<String> status = repo.getCategoryConsent(
                "user-1",
                "PII",
                "analytics"
            );
            assertTrue(status.isEmpty());
        }

        @Test
        @DisplayName(
            "removeCategoryConsent is a no-op when entry does not exist"
        )
        void removeCategoryNoOp() {
            assertDoesNotThrow(() ->
                repo.removeCategoryConsent("user-1", "PII", "analytics")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Batch writes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Batch writes")
    class BatchWrites {

        @Test
        @DisplayName("setFieldConsentBatch stores multiple field entries")
        void batchFieldConsent() {
            Map<String, Boolean> entries = new LinkedHashMap<>();
            entries.put("ssn:marketing", true);
            entries.put("email:marketing", false);
            entries.put("phone:analytics", true);

            repo.setFieldConsentBatch("user-1", entries);

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                repo.getFieldConsent("user-1", "ssn", "marketing").orElse(null)
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                repo
                    .getFieldConsent("user-1", "email", "marketing")
                    .orElse(null)
            );
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                repo
                    .getFieldConsent("user-1", "phone", "analytics")
                    .orElse(null)
            );
        }

        @Test
        @DisplayName("setCategoryConsentBatch stores multiple category entries")
        void batchCategoryConsent() {
            Map<String, Boolean> entries = new LinkedHashMap<>();
            entries.put("PII:marketing", true);
            entries.put("Financial:analytics", false);

            repo.setCategoryConsentBatch("user-1", entries);

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                repo
                    .getCategoryConsent("user-1", "PII", "marketing")
                    .orElse(null)
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                repo
                    .getCategoryConsent("user-1", "Financial", "analytics")
                    .orElse(null)
            );
        }

        @Test
        @DisplayName("batch write with empty map is a no-op")
        void batchEmptyMap() {
            assertDoesNotThrow(() ->
                repo.setFieldConsentBatch("user-1", Map.of())
            );
            assertDoesNotThrow(() ->
                repo.setCategoryConsentBatch("user-1", Map.of())
            );
            assertFalse(repo.exists("user-1"));
        }
    }

    // -----------------------------------------------------------------------
    // evaluateScopes — the hot path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateScopes")
    class EvaluateScopes {

        @Test
        @DisplayName("returns GRANTED when field-level consent exists")
        void fieldLevelGrant() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("falls back to category-level when no field-level consent")
        void categoryFallback() {
            // Only category-level consent is set — no field-level for "ssn"
            repo.setCategoryConsent("user-1", "PII", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "field-level GRANTED wins even when category-level is REVOKED"
        )
        void fieldOverridesCategory() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setCategoryConsent("user-1", "PII", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "returns REVOKED when field-level is REVOKED and no category grant"
        )
        void fieldRevoked() {
            repo.setFieldConsent("user-1", "ssn", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("returns REVOKED when only category-level is REVOKED")
        void categoryRevoked() {
            repo.setCategoryConsent("user-1", "PII", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("returns null when no consent record exists at any level")
        void noRecord() {
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertNull(result.get(scope).effectiveStatus());
        }

        @Test
        @DisplayName(
            "evaluates a mixed batch of granted, revoked, and missing scopes"
        )
        void mixedBatch() {
            // ssn → field-level GRANTED
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            // email → category-level GRANTED (no direct field)
            repo.setCategoryConsent("user-1", "PII", "analytics", true);
            // phone → field-level REVOKED
            repo.setFieldConsent("user-1", "phone", "marketing", false);
            // dob → no consent at any level

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "analytics");
            ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");
            ConsentScope dob = ConsentScope.to("PII", "dob", "marketing");

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(ssn, email, phone, dob)
            );

            assertEquals(4, result.size());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(ssn).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(email).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(phone).effectiveStatus()
            );
            assertNull(result.get(dob).effectiveStatus());
        }

        @Test
        @DisplayName("returns empty map for empty request set")
        void emptyRequest() {
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of()
            );
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty map for null request set")
        void nullRequest() {
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                null
            );
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName(
            "field-level REVOKED takes precedence over category-level GRANTED"
        )
        void fieldRevokedTakesPrecedenceOverCategoryGrant() {
            // Field is explicitly revoked — most specific wins, category cannot override
            repo.setFieldConsent("user-1", "ssn", "marketing", false);
            repo.setCategoryConsent("user-1", "PII", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            // Most-specific-wins: field-level REVOKED takes precedence
            // over category-level GRANTED
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("different purpose names are isolated")
        void purposeIsolation() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            // ssn for analytics has no consent

            ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(marketing, analytics)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(marketing).effectiveStatus()
            );
            assertNull(result.get(analytics).effectiveStatus());
        }
    }

    // -----------------------------------------------------------------------
    // Subject isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Subject isolation")
    class SubjectIsolation {

        @Test
        @DisplayName("consent for one subject does not leak to another")
        void noLeakBetweenSubjects() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);

            Optional<String> user1 = repo.getFieldConsent(
                "user-1",
                "ssn",
                "marketing"
            );
            Optional<String> user2 = repo.getFieldConsent(
                "user-2",
                "ssn",
                "marketing"
            );

            assertTrue(user1.isPresent());
            assertTrue(user2.isEmpty());
        }

        @Test
        @DisplayName("evaluateScopes isolates per subject")
        void evaluateIsolation() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setCategoryConsent("user-2", "PII", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> r1 = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );
            Map<ConsentScope, DecisionDetail> r2 = repo.evaluateScopes(
                "user-2",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                r1.get(scope).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                r2.get(scope).effectiveStatus()
            );

            // user-3 has no consent
            Map<ConsentScope, DecisionDetail> r3 = repo.evaluateScopes(
                "user-3",
                Set.of(scope)
            );
            assertNull(r3.get(scope).effectiveStatus());
        }
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("getAllConsent returns all hash entries for a subject")
        void getAllConsent() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setCategoryConsent("user-1", "PII", "analytics", false);

            Map<String, String> all = repo.getAllConsent("user-1");

            assertEquals(2, all.size());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                all.get(
                    ConsentPolicyRedisRepository.fieldHashKey(
                        "ssn",
                        "marketing"
                    )
                )
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                all.get(
                    ConsentPolicyRedisRepository.categoryHashKey(
                        "PII",
                        "analytics"
                    )
                )
            );
        }

        @Test
        @DisplayName("getAllConsent returns empty map for unknown subject")
        void getAllConsentEmpty() {
            Map<String, String> all = repo.getAllConsent("unknown");
            assertTrue(all.isEmpty());
        }

        @Test
        @DisplayName("removeAllConsent deletes the entire subject hash")
        void removeAllConsent() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setCategoryConsent("user-1", "PII", "analytics", true);

            boolean removed = repo.removeAllConsent("user-1");

            assertTrue(removed);
            assertFalse(repo.exists("user-1"));
            assertTrue(repo.getAllConsent("user-1").isEmpty());
        }

        @Test
        @DisplayName("removeAllConsent returns false for unknown subject")
        void removeAllConsentMiss() {
            boolean removed = repo.removeAllConsent("unknown");
            assertFalse(removed);
        }

        @Test
        @DisplayName("exists returns true when subject has consent data")
        void existsTrue() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            assertTrue(repo.exists("user-1"));
        }

        @Test
        @DisplayName("exists returns false for unknown subject")
        void existsFalse() {
            assertFalse(repo.exists("unknown"));
        }

        @Test
        @DisplayName("removeAllConsent for one subject does not affect another")
        void removeAllIsolation() {
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            repo.setFieldConsent("user-2", "ssn", "marketing", true);

            repo.removeAllConsent("user-1");

            assertFalse(repo.exists("user-1"));
            assertTrue(repo.exists("user-2"));
        }
    }

    // -----------------------------------------------------------------------
    // resolveScopes — pure function (no Redis required)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolveScopes (pure computation, no Redis)")
    class ResolveScopesPure {

        @Test
        @DisplayName("field-level GRANTED resolves immediately")
        void fieldGranted() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "GRANTED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("falls back to category-level when no field-level entry")
        void categoryFallback() {
            Map<String, String> allConsent = Map.of(
                "c:PII:marketing",
                "GRANTED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("field-level GRANTED wins over category-level REVOKED")
        void fieldOverridesCategory() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "GRANTED",
                "c:PII:marketing",
                "REVOKED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "field-level REVOKED takes precedence over category-level GRANTED"
        )
        void fieldRevokedTakesPrecedenceOverCategoryGrant() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "REVOKED",
                "c:PII:marketing",
                "GRANTED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            // Most-specific-wins: field-level REVOKED takes precedence
            // over category-level GRANTED
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "field-level REVOKED with no category entry returns REVOKED"
        )
        void fieldRevokedNoCategory() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "REVOKED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "category-level REVOKED with no field entry returns REVOKED"
        )
        void categoryRevokedNoField() {
            Map<String, String> allConsent = Map.of(
                "c:PII:marketing",
                "REVOKED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName("no matching entries at all returns null")
        void noMatch() {
            Map<String, String> allConsent = Map.of(
                "f:email:analytics",
                "GRANTED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertNull(result.get(scope).effectiveStatus());
        }

        @Test
        @DisplayName("empty consent map denies everything")
        void emptyConsentMap() {
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    Map.of(),
                    Set.of(scope),
                    Instant.now()
                );

            assertNull(result.get(scope).effectiveStatus());
        }

        @Test
        @DisplayName("empty request set returns empty map")
        void emptyRequestSet() {
            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    Map.of("f:ssn:marketing", "GRANTED"),
                    Set.of(),
                    Instant.now()
                );

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null request set returns empty map")
        void nullRequestSet() {
            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    Map.of(),
                    null,
                    Instant.now()
                );

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("mixed batch resolves each scope independently")
        void mixedBatch() {
            Map<String, String> allConsent = new HashMap<>();
            allConsent.put("f:ssn:marketing", "GRANTED");
            allConsent.put("c:PII:analytics", "GRANTED");
            allConsent.put("f:phone:marketing", "REVOKED");
            // dob has nothing

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "analytics");
            ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");
            ConsentScope dob = ConsentScope.to("PII", "dob", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(ssn, email, phone, dob),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(ssn).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(email).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(phone).effectiveStatus()
            );
            assertNull(result.get(dob).effectiveStatus());
        }

        @Test
        @DisplayName("unrelated hash entries are ignored")
        void unrelatedEntriesIgnored() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "GRANTED",
                "f:email:analytics",
                "REVOKED",
                "c:Financial:reporting",
                "GRANTED"
            );
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(scope),
                    Instant.now()
                );

            assertEquals(1, result.size());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(scope).effectiveStatus()
            );
        }

        @Test
        @DisplayName(
            "purpose names are part of the key — different purposes don't cross"
        )
        void purposeIsolation() {
            Map<String, String> allConsent = Map.of(
                "f:ssn:marketing",
                "GRANTED",
                "c:PII:marketing",
                "GRANTED"
            );

            ConsentScope marketing = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope analytics = ConsentScope.to("PII", "ssn", "analytics");

            Map<ConsentScope, DecisionDetail> result =
                ConsentPolicyRedisRepository.resolveScopes(
                    allConsent,
                    Set.of(marketing, analytics),
                    Instant.now()
                );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(marketing).effectiveStatus()
            );
            assertNull(result.get(analytics).effectiveStatus());
        }
    }

    // -----------------------------------------------------------------------
    // Key helpers (package-visible static methods)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Key formatting")
    class KeyFormatting {

        @Test
        @DisplayName("subjectKey uses correct prefix")
        void subjectKey() {
            assertEquals(
                "operio:consent:user-42",
                ConsentPolicyRedisRepository.subjectKey("user-42")
            );
        }

        @Test
        @DisplayName("fieldHashKey uses f: prefix")
        void fieldHashKey() {
            assertEquals(
                "f:ssn:marketing",
                ConsentPolicyRedisRepository.fieldHashKey("ssn", "marketing")
            );
        }

        @Test
        @DisplayName("categoryHashKey uses c: prefix")
        void categoryHashKey() {
            assertEquals(
                "c:PII:analytics",
                ConsentPolicyRedisRepository.categoryHashKey("PII", "analytics")
            );
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
            // If @BeforeEach flushAll works, no keys should exist
            assertFalse(repo.exists("user-1"));
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            // Write something — next test should still see empty DB
            repo.setFieldConsent("user-1", "ssn", "marketing", true);
            assertTrue(repo.exists("user-1"));
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            assertFalse(repo.exists("user-1"));
        }
    }
}
