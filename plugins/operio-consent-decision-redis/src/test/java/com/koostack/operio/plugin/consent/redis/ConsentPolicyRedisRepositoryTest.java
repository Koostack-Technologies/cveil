package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository.AffectedEntry;
import java.io.IOException;
import java.util.List;
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
 * Integration tests for {@link ConsentPolicyRedisRepository} backed by an
 * embedded {@link RedisServer} (jedis-mock) — no Docker required.
 *
 * <p>Each {@code @Nested} class groups related scenarios. {@code @BeforeEach}
 * flushes the entire Redis database so every test starts from a clean slate.
 *
 * <p>The repository uses a Hash-per-subject schema with reverse indices:
 * {@code {tenant}:consent:{subject}} → Hash of {@code c:{cat}:{purpose}}
 * and {@code f:{entity/field}:{purpose}} fields. Evaluation uses a single
 * {@code HMGET} round trip with 2N hash fields for field-over-category precedence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConsentPolicyRedisRepository")
class ConsentPolicyRedisRepositoryTest {

    private static final String TENANT = "test-tenant";

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
    // qualifyField helper
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("qualifyField helper")
    class QualifyField {

        @Test
        @DisplayName("uses entity/field format")
        void entityAndField() {
            assertEquals(
                "User/email",
                ConsentPolicyRedisRepository.qualifyField("User", "email")
            );
        }

        @Test
        @DisplayName("uses DEFAULT_ENTITY when entity is null")
        void nullEntity() {
            assertEquals(
                "_default_/ssn",
                ConsentPolicyRedisRepository.qualifyField(null, "ssn")
            );
        }

        @Test
        @DisplayName("uses DEFAULT_ENTITY when entity is blank")
        void blankEntity() {
            assertEquals(
                "_default_/ssn",
                ConsentPolicyRedisRepository.qualifyField("  ", "ssn")
            );
        }

        @Test
        @DisplayName("uses DEFAULT_ENTITY when entity is wildcard '*'")
        void wildcardEntity() {
            assertEquals(
                "_default_/ssn",
                ConsentPolicyRedisRepository.qualifyField("*", "ssn")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Key helpers")
    class KeyHelpers {

        @Test
        @DisplayName("consentHashKey uses {tenant}:consent:{subject}")
        void consentHashKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(
                    TENANT + ":consent:user-42",
                    repo.consentHashKey("user-42")
                );
                return null;
            });
        }

        @Test
        @DisplayName("idxCatKey uses {tenant}:idx:cat:{category}")
        void idxCatKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(
                    TENANT + ":idx:cat:PII",
                    repo.idxCatKey("PII")
                );
                return null;
            });
        }

        @Test
        @DisplayName("idxFieldKey uses {tenant}:idx:field:{qualifiedField}")
        void idxFieldKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(
                    TENANT + ":idx:field:User/email",
                    repo.idxFieldKey("User/email")
                );
                return null;
            });
        }

        @Test
        @DisplayName("idxPurposeKey uses {tenant}:idx:purpose:{purpose}")
        void idxPurposeKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(
                    TENANT + ":idx:purpose:marketing",
                    repo.idxPurposeKey("marketing")
                );
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // setCategoryConsent / setFieldConsent — basic writes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setCategoryConsent")
    class SetCategoryConsent {

        @Test
        @DisplayName("stores GRANTED in hash as c:{category}:{purpose}")
        void grantCategory() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                String hashKey = repo.consentHashKey("user-1");
                String val = jedis.hget(hashKey, "c:PII:marketing");
                assertEquals("GRANTED", val);
                return null;
            });
        }

        @Test
        @DisplayName("stores REVOKED in hash as c:{category}:{purpose}")
        void revokeCategory() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", false);

                String hashKey = repo.consentHashKey("user-1");
                String val = jedis.hget(hashKey, "c:PII:marketing");
                assertEquals("REVOKED", val);
                return null;
            });
        }

        @Test
        @DisplayName("populates category reverse index")
        void categoryReverseIndex() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                Set<String> members = jedis.smembers(repo.idxCatKey("PII"));
                assertEquals(1, members.size());
                assertTrue(members.iterator().next().startsWith("user-1"));
                return null;
            });
        }

        @Test
        @DisplayName("populates purpose reverse index")
        void purposeReverseIndex() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                Set<String> members = jedis.smembers(
                    repo.idxPurposeKey("marketing")
                );
                assertEquals(1, members.size());
                return null;
            });
        }
    }

    @Nested
    @DisplayName("setFieldConsent")
    class SetFieldConsent {

        @Test
        @DisplayName("stores GRANTED in hash as f:{entity/field}:{purpose}")
        void grantField() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", "User", "email", "marketing", true
                );

                String hashKey = repo.consentHashKey("user-1");
                String val = jedis.hget(hashKey, "f:User/email:marketing");
                assertEquals("GRANTED", val);
                return null;
            });
        }

        @Test
        @DisplayName("uses DEFAULT_ENTITY when entity is null")
        void nullEntity() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                String hashKey = repo.consentHashKey("user-1");
                String val = jedis.hget(hashKey, "f:_default_/ssn:marketing");
                assertEquals("GRANTED", val);
                return null;
            });
        }

        @Test
        @DisplayName("populates field reverse index")
        void fieldReverseIndex() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                Set<String> members = jedis.smembers(
                    repo.idxFieldKey("_default_/ssn")
                );
                assertEquals(1, members.size());
                return null;
            });
        }

        @Test
        @DisplayName("overwrite replaces the existing value")
        void overwrite() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", false
                );

                String hashKey = repo.consentHashKey("user-1");
                assertEquals(
                    "REVOKED",
                    jedis.hget(hashKey, "f:_default_/ssn:marketing")
                );
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluate — single scope with field-over-category precedence
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluate — single scope")
    class Evaluate {

        @Test
        @DisplayName("returns GRANTED|FIELD for a field-level grant")
        void fieldGrant() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertEquals("GRANTED", d.effectiveStatus());
                assertEquals("FIELD", d.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("returns GRANTED|CATEGORY for a category-level grant")
        void categoryGrant() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertEquals("GRANTED", d.effectiveStatus());
                assertEquals("CATEGORY", d.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("field-level takes precedence over category-level")
        void fieldPrecedence() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", false
                );

                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertEquals("REVOKED", d.effectiveStatus());
                assertEquals("FIELD", d.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("returns null status when no entry exists")
        void noRecord() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertNull(d.effectiveStatus());
                assertEquals(DecisionDetail.RESOLVED_BY_NONE, d.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("expiresAt is always null")
        void expiresAtNull() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertNull(d.expiresAt());
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // evaluateScopes — batch evaluation (HMGET)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateScopes (HMGET)")
    class EvaluateScopes {

        @Test
        @DisplayName("returns GRANTED for a field-sourced grant")
        void fieldGrant() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                ConsentScope scope = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", Set.of(scope));

                assertEquals(1, result.size());
                DecisionDetail detail = result.get(scope);
                assertNotNull(detail);
                assertEquals("GRANTED", detail.effectiveStatus());
                assertEquals("FIELD", detail.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("returns GRANTED for a category-sourced grant")
        void categoryGrant() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                ConsentScope scope = ConsentScope.forCategory(
                    "PII", "marketing"
                );
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", Set.of(scope));

                DecisionDetail detail = result.get(scope);
                assertEquals("GRANTED", detail.effectiveStatus());
                assertEquals("CATEGORY", detail.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("field-level takes precedence in batch evaluation")
        void fieldPrecedenceInBatch() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", false
                );

                ConsentScope scope = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", Set.of(scope));

                DecisionDetail detail = result.get(scope);
                assertEquals("REVOKED", detail.effectiveStatus());
                assertEquals("FIELD", detail.resolvedBy());
                return null;
            });
        }

        @Test
        @DisplayName("returns null effectiveStatus when no entry exists")
        void noRecord() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                ConsentScope scope = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", Set.of(scope));

                assertEquals(1, result.size());
                DecisionDetail detail = result.get(scope);
                assertNull(detail.effectiveStatus());
                assertEquals(
                    DecisionDetail.RESOLVED_BY_NONE, detail.resolvedBy()
                );
                return null;
            });
        }

        @Test
        @DisplayName("evaluates a mixed batch of granted, revoked, and missing")
        void mixedBatch() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "email", "marketing", false
                );

                ConsentScope ssn = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );
                ConsentScope email = new ConsentScope(
                    "PII", "email", null, "marketing"
                );
                ConsentScope phone = new ConsentScope(
                    "PII", "phone", null, "marketing"
                );

                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes(
                        "user-1", Set.of(ssn, email, phone)
                    );

                assertEquals(3, result.size());
                assertEquals("GRANTED", result.get(ssn).effectiveStatus());
                assertEquals("REVOKED", result.get(email).effectiveStatus());
                assertNull(result.get(phone).effectiveStatus());
                return null;
            });
        }

        @Test
        @DisplayName("returns empty map for empty request set")
        void emptyRequest() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", Set.of());
                assertTrue(result.isEmpty());
                return null;
            });
        }

        @Test
        @DisplayName("returns empty map for null request set")
        void nullRequest() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", null);
                assertTrue(result.isEmpty());
                return null;
            });
        }

        @Test
        @DisplayName("different purposes are isolated")
        void purposeIsolation() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                ConsentScope marketing = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );
                ConsentScope analytics = new ConsentScope(
                    "PII", "ssn", null, "analytics"
                );

                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes(
                        "user-1", Set.of(marketing, analytics)
                    );

                assertEquals("GRANTED", result.get(marketing).effectiveStatus());
                assertNull(result.get(analytics).effectiveStatus());
                return null;
            });
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
        void noLeak() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                DecisionDetail d1 = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                DecisionDetail d2 = repo.evaluate(
                    "user-2", "PII", null, "ssn", "marketing"
                );

                assertEquals("GRANTED", d1.effectiveStatus());
                assertNull(d2.effectiveStatus());
                return null;
            });
        }

        @Test
        @DisplayName("evaluateScopes isolates per subject")
        void evaluateIsolation() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-2", null, "ssn", "marketing", false
                );

                ConsentScope scope = new ConsentScope(
                    "PII", "ssn", null, "marketing"
                );

                Map<ConsentScope, DecisionDetail> r1 =
                    repo.evaluateScopes("user-1", Set.of(scope));
                Map<ConsentScope, DecisionDetail> r2 =
                    repo.evaluateScopes("user-2", Set.of(scope));
                Map<ConsentScope, DecisionDetail> r3 =
                    repo.evaluateScopes("user-3", Set.of(scope));

                assertEquals("GRANTED", r1.get(scope).effectiveStatus());
                assertEquals("REVOKED", r2.get(scope).effectiveStatus());
                assertNull(r3.get(scope).effectiveStatus());
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // removeAllConsent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeAllConsent")
    class RemoveAllConsent {

        @Test
        @DisplayName("removes all hash entries for a subject")
        void removesAll() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "email", "analytics", false
                );
                repo.setCategoryConsent(
                    "user-1", "PII", "marketing", true
                );

                repo.removeAllConsent("user-1");

                String hashKey = repo.consentHashKey("user-1");
                assertEquals(0, jedis.hlen(hashKey));
                return null;
            });
        }

        @Test
        @DisplayName("is a no-op for a subject with no consent")
        void noConsent() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.removeAllConsent("user-1");
                // no exception
                return null;
            });
        }

        @Test
        @DisplayName("does not affect other subjects")
        void isolation() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-2", null, "ssn", "marketing", true
                );

                repo.removeAllConsent("user-1");

                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-1"),
                        "f:_default_/ssn:marketing"
                    )
                );
                assertEquals(
                    "GRANTED",
                    jedis.hget(
                        repo.consentHashKey("user-2"),
                        "f:_default_/ssn:marketing"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("cleans up reverse indices")
        void cleansReverseIndices() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setCategoryConsent(
                    "user-1", "PII", "marketing", true
                );

                repo.removeAllConsent("user-1");

                assertTrue(
                    jedis.smembers(repo.idxFieldKey("_default_/ssn")).isEmpty()
                );
                assertTrue(
                    jedis.smembers(repo.idxCatKey("PII")).isEmpty()
                );
                assertTrue(
                    jedis.smembers(repo.idxPurposeKey("marketing")).isEmpty()
                );
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // deleteAllCachedKeysForSubject (returns count)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("deleteAllCachedKeysForSubject")
    class DeleteAllCachedKeys {

        @Test
        @DisplayName("returns count of removed entries")
        void returnsCount() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "email", "analytics", false
                );

                long removed = repo.deleteAllCachedKeysForSubject("user-1");
                assertEquals(2, removed);
                return null;
            });
        }

        @Test
        @DisplayName("returns 0 when no consent exists")
        void returnsZero() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                long removed = repo.deleteAllCachedKeysForSubject("user-1");
                assertEquals(0, removed);
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // countCachedKeysForSubject
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("countCachedKeysForSubject")
    class CountCachedKeys {

        @Test
        @DisplayName("returns count of hash entries")
        void returnsCount() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setCategoryConsent(
                    "user-1", "PII", "analytics", true
                );

                assertEquals(2, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }

        @Test
        @DisplayName("returns 0 when no consent exists")
        void returnsZero() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(0, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // deleteByCategory — reverse index cleanup
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("deleteByCategory")
    class DeleteByCategory {

        @Test
        @DisplayName("removes category entries and returns affected list")
        void removesEntries() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                repo.setCategoryConsent("user-2", "PII", "analytics", true);

                List<AffectedEntry> affected = repo.deleteByCategory("PII");

                assertEquals(2, affected.size());

                // Hash entries should be deleted
                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-1"), "c:PII:marketing"
                    )
                );
                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-2"), "c:PII:analytics"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("removes the category reverse index key")
        void removesIdxKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                repo.deleteByCategory("PII");

                assertFalse(jedis.exists(repo.idxCatKey("PII")));
                return null;
            });
        }

        @Test
        @DisplayName("cleans up purpose reverse index entries")
        void cleansPurposeIdx() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                repo.deleteByCategory("PII");

                assertTrue(
                    jedis.smembers(repo.idxPurposeKey("marketing")).isEmpty()
                );
                return null;
            });
        }

        @Test
        @DisplayName("does not affect field-level entries")
        void fieldEntriesUnaffected() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                repo.deleteByCategory("PII");

                // Field entry still present
                assertEquals(
                    "GRANTED",
                    jedis.hget(
                        repo.consentHashKey("user-1"),
                        "f:_default_/ssn:marketing"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("returns empty list when no entries exist")
        void emptyWhenNone() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                List<AffectedEntry> affected =
                    repo.deleteByCategory("NonExistent");
                assertTrue(affected.isEmpty());
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // deleteByField — reverse index cleanup
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("deleteByField")
    class DeleteByField {

        @Test
        @DisplayName("removes field entries and returns affected list")
        void removesEntries() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-2", null, "ssn", "analytics", true
                );

                List<AffectedEntry> affected =
                    repo.deleteByField("_default_/ssn");

                assertEquals(2, affected.size());

                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-1"),
                        "f:_default_/ssn:marketing"
                    )
                );
                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-2"),
                        "f:_default_/ssn:analytics"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("removes the field reverse index key")
        void removesIdxKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                repo.deleteByField("_default_/ssn");

                assertFalse(jedis.exists(repo.idxFieldKey("_default_/ssn")));
                return null;
            });
        }

        @Test
        @DisplayName("does not affect category-level entries")
        void categoryEntriesUnaffected() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                repo.deleteByField("_default_/ssn");

                assertEquals(
                    "GRANTED",
                    jedis.hget(
                        repo.consentHashKey("user-1"), "c:PII:marketing"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("returns empty list when no entries exist")
        void emptyWhenNone() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                List<AffectedEntry> affected =
                    repo.deleteByField("_default_/nonexistent");
                assertTrue(affected.isEmpty());
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // deleteByPurpose — reverse index cleanup
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("deleteByPurpose")
    class DeleteByPurpose {

        @Test
        @DisplayName("removes all entries for a purpose across subjects")
        void removesEntries() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setCategoryConsent("user-2", "PII", "marketing", true);

                List<AffectedEntry> affected =
                    repo.deleteByPurpose("marketing");

                assertEquals(2, affected.size());

                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-1"),
                        "f:_default_/ssn:marketing"
                    )
                );
                assertNull(
                    jedis.hget(
                        repo.consentHashKey("user-2"), "c:PII:marketing"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("removes the purpose reverse index key")
        void removesIdxKey() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );

                repo.deleteByPurpose("marketing");

                assertFalse(jedis.exists(repo.idxPurposeKey("marketing")));
                return null;
            });
        }

        @Test
        @DisplayName("cleans up field and category reverse index entries")
        void cleansTypeIndices() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setCategoryConsent("user-1", "PII", "marketing", true);

                repo.deleteByPurpose("marketing");

                assertTrue(
                    jedis.smembers(repo.idxFieldKey("_default_/ssn")).isEmpty()
                );
                assertTrue(
                    jedis.smembers(repo.idxCatKey("PII")).isEmpty()
                );
                return null;
            });
        }

        @Test
        @DisplayName("does not affect entries with different purposes")
        void otherPurposesUnaffected() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "ssn", "analytics", true
                );

                repo.deleteByPurpose("marketing");

                assertEquals(
                    "GRANTED",
                    jedis.hget(
                        repo.consentHashKey("user-1"),
                        "f:_default_/ssn:analytics"
                    )
                );
                return null;
            });
        }

        @Test
        @DisplayName("returns empty list when no entries exist")
        void emptyWhenNone() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                List<AffectedEntry> affected =
                    repo.deleteByPurpose("nonexistent");
                assertTrue(affected.isEmpty());
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // Large batch
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Large batch")
    class LargeBatch {

        @Test
        @DisplayName("evaluates 100 scopes in a single HMGET")
        void hundredScopes() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                // Write 50 field-level entries
                for (int i = 0; i < 50; i++) {
                    repo.setFieldConsent(
                        "user-1", null, "field_" + i, "marketing", true
                    );
                }
                // Write 50 category-level entries
                for (int i = 50; i < 100; i++) {
                    repo.setCategoryConsent(
                        "user-1", "cat_" + i, "analytics", true
                    );
                }

                // Build 100 scopes — 50 field + 50 category
                Set<ConsentScope> scopes = new java.util.HashSet<>();
                for (int i = 0; i < 50; i++) {
                    scopes.add(
                        new ConsentScope(
                            "PII", "field_" + i, null, "marketing"
                        )
                    );
                }
                for (int i = 50; i < 100; i++) {
                    scopes.add(
                        ConsentScope.forCategory("cat_" + i, "analytics")
                    );
                }

                Map<ConsentScope, DecisionDetail> result =
                    repo.evaluateScopes("user-1", scopes);

                assertEquals(100, result.size());
                for (DecisionDetail detail : result.values()) {
                    assertEquals("GRANTED", detail.effectiveStatus());
                }
                return null;
            });
        }
    }

    // -----------------------------------------------------------------------
    // Tenant isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("different tenants do not see each other's data")
        void crossTenantIsolation() throws Exception {
            TenantContext.callWhere("acme", () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", false
                );
                return null;
            });

            TenantContext.callWhere("acme", () -> {
                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertEquals("GRANTED", d.effectiveStatus());
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                DecisionDetail d = repo.evaluate(
                    "user-1", "PII", null, "ssn", "marketing"
                );
                assertEquals("REVOKED", d.effectiveStatus());
                return null;
            });
        }

        @Test
        @DisplayName("removeAllConsent only affects active tenant")
        void removeAllConsentTenantIsolation() throws Exception {
            TenantContext.callWhere("acme", () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                repo.setFieldConsent(
                    "user-1", null, "email", "marketing", true
                );
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                return null;
            });

            TenantContext.callWhere("acme", () -> {
                long removed = repo.deleteAllCachedKeysForSubject("user-1");
                assertEquals(2, removed);
                return null;
            });

            TenantContext.callWhere("acme", () -> {
                assertEquals(0, repo.countCachedKeysForSubject("user-1"));
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                assertEquals(1, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }

        @Test
        @DisplayName("deleteByCategory only affects active tenant")
        void deleteByCategoryTenantIsolation() throws Exception {
            TenantContext.callWhere("acme", () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                repo.setCategoryConsent("user-1", "PII", "marketing", true);
                return null;
            });

            TenantContext.callWhere("acme", () -> {
                List<AffectedEntry> affected = repo.deleteByCategory("PII");
                assertEquals(1, affected.size());
                return null;
            });

            // globex data still present
            TenantContext.callWhere("globex", () -> {
                assertEquals(
                    "GRANTED",
                    jedis.hget(
                        repo.consentHashKey("user-1"), "c:PII:marketing"
                    )
                );
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
        void emptyRun1() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(0, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                repo.setFieldConsent(
                    "user-1", null, "ssn", "marketing", true
                );
                assertEquals(1, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() throws Exception {
            TenantContext.callWhere(TENANT, () -> {
                assertEquals(0, repo.countCachedKeysForSubject("user-1"));
                return null;
            });
        }
    }
}
