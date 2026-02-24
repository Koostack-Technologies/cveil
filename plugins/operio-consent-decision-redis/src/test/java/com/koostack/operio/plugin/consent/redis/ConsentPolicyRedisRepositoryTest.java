package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository.ConsentValue;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *
 * <p>The repository uses a flat key-per-field schema:
 * {@code operio:consent:{subject}:{field}:{purpose}} → {@code STATUS|SOURCE}.
 * Evaluation uses a single {@code MGET} round trip.
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
    // Value encoding / decoding
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Value encoding / decoding")
    class ValueEncoding {

        @Test
        @DisplayName("encodeValue produces STATUS|SOURCE format")
        void encodeValue() {
            assertEquals(
                "GRANTED|FIELD",
                ConsentPolicyRedisRepository.encodeValue("GRANTED", "FIELD")
            );
            assertEquals(
                "REVOKED|CATEGORY",
                ConsentPolicyRedisRepository.encodeValue("REVOKED", "CATEGORY")
            );
        }

        @Test
        @DisplayName("parseValue handles GRANTED|FIELD")
        void parseGrantedField() {
            ConsentValue cv = ConsentPolicyRedisRepository.parseValue(
                "GRANTED|FIELD"
            );
            assertNotNull(cv);
            assertEquals("GRANTED", cv.status());
            assertEquals("FIELD", cv.source());
        }

        @Test
        @DisplayName("parseValue handles REVOKED|CATEGORY")
        void parseRevokedCategory() {
            ConsentValue cv = ConsentPolicyRedisRepository.parseValue(
                "REVOKED|CATEGORY"
            );
            assertNotNull(cv);
            assertEquals("REVOKED", cv.status());
            assertEquals("CATEGORY", cv.source());
        }

        @Test
        @DisplayName("parseValue returns null for null input")
        void parseNull() {
            assertNull(ConsentPolicyRedisRepository.parseValue(null));
        }

        @Test
        @DisplayName(
            "parseValue handles bare status without source (defensive)"
        )
        void parseBareStatus() {
            ConsentValue cv = ConsentPolicyRedisRepository.parseValue(
                "GRANTED"
            );
            assertNotNull(cv);
            assertEquals("GRANTED", cv.status());
            assertEquals("FIELD", cv.source());
        }
    }

    // -----------------------------------------------------------------------
    // Key formatting
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Key formatting")
    class KeyFormatting {

        @Test
        @DisplayName("consentKey uses correct format")
        void consentKey() {
            assertEquals(
                "operio:consent:user-42:_default_/ssn:marketing",
                repo.consentKey("user-42", null, "ssn", "marketing")
            );
        }

        @Test
        @DisplayName("consentKey preserves segments with special characters")
        void consentKeySpecialChars() {
            assertEquals(
                "operio:consent:user@org:_default_/field.name:purpose-1",
                repo.consentKey("user@org", null, "field.name", "purpose-1")
            );
        }
    }

    // -----------------------------------------------------------------------
    // setConsent / getConsent — generic
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setConsent / getConsent — generic")
    class GenericConsent {

        @Test
        @DisplayName("set and get a GRANTED|FIELD entry")
        void setGetGrantedField() {
            repo.setConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                ConsentPolicyRedisRepository.SOURCE_FIELD
            );

            Optional<String> status = repo.getConsent(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.GRANTED, status.get());
        }

        @Test
        @DisplayName("set and get a REVOKED|CATEGORY entry")
        void setGetRevokedCategory() {
            repo.setConsent(
                "user-1",
                null,
                "email",
                "analytics",
                false,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );

            Optional<ConsentValue> entry = repo.getConsentEntry(
                "user-1",
                null,
                "email",
                "analytics"
            );
            assertTrue(entry.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                entry.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                entry.get().source()
            );
        }

        @Test
        @DisplayName("getConsent returns empty for missing key")
        void getMiss() {
            Optional<String> status = repo.getConsent(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(status.isEmpty());
        }

        @Test
        @DisplayName("getConsentEntry returns empty for missing key")
        void getEntryMiss() {
            Optional<ConsentValue> entry = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(entry.isEmpty());
        }

        @Test
        @DisplayName("overwrite replaces the existing value")
        void overwrite() {
            repo.setConsent("user-1", null, "ssn", "marketing", true, "FIELD");
            repo.setConsent("user-1", null, "ssn", "marketing", false, "FIELD");

            Optional<String> status = repo.getConsent(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.REVOKED, status.get());
        }
    }

    // -----------------------------------------------------------------------
    // setFieldConsent — convenience
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setFieldConsent — convenience")
    class FieldConsentConvenience {

        @Test
        @DisplayName("setFieldConsent stores with SOURCE_FIELD")
        void fieldConsentSource() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            Optional<ConsentValue> entry = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                entry.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                entry.get().source()
            );
        }

        @Test
        @DisplayName("setFieldConsent revoke stores REVOKED|FIELD")
        void fieldConsentRevoke() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", false);

            Optional<ConsentValue> entry = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(entry.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                entry.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                entry.get().source()
            );
        }

        @Test
        @DisplayName("setFieldConsent with expiresAt sets TTL")
        void fieldConsentWithExpiry() {
            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            repo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                expiresAt
            );

            Optional<String> status = repo.getConsent(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());
            assertEquals(ConsentPolicyRedisRepository.GRANTED, status.get());

            long ttl = repo.ttl("user-1", null, "ssn", "marketing");
            // Should have a positive TTL (roughly 3600 seconds, allow some slack)
            assertTrue(
                ttl > 3500 && ttl <= 3600,
                "Expected TTL ~3600, got: " + ttl
            );
        }

        @Test
        @DisplayName("setFieldConsent with past expiresAt is a no-op")
        void fieldConsentExpiredAlready() {
            Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
            repo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                past
            );

            // Key should not exist — TTL was non-positive so write was skipped
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("setFieldConsent with null expiresAt has no TTL")
        void fieldConsentNullExpiry() {
            repo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                null
            );

            Optional<String> status = repo.getConsent(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(status.isPresent());

            long ttl = repo.ttl("user-1", null, "ssn", "marketing");
            assertEquals(-1, ttl, "Expected no TTL (-1)");
        }
    }

    // -----------------------------------------------------------------------
    // setCategoryConsentForFields — fan-out
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setCategoryConsentForFields — fan-out")
    class CategoryFanOut {

        @Test
        @DisplayName("fans out to all provided fields with SOURCE_CATEGORY")
        void fanOutGranted() {
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
            );

            for (String field : Set.of("ssn", "email", "phone")) {
                Optional<ConsentValue> entry = repo.getConsentEntry(
                    "user-1",
                    null,
                    field,
                    "marketing"
                );
                assertTrue(entry.isPresent(), "Missing entry for " + field);
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    entry.get().status()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    entry.get().source()
                );
            }
        }

        @Test
        @DisplayName("fans out revoked status")
        void fanOutRevoked() {
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn", "_default_/email")
            );

            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                ssn.get().status()
            );
        }

        @Test
        @DisplayName("fans out with TTL when expiresAt provided")
        void fanOutWithExpiry() {
            Instant expiresAt = Instant.now().plus(2, ChronoUnit.HOURS);
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                expiresAt,
                Set.of("_default_/ssn", "_default_/email")
            );

            for (String field : Set.of("ssn", "email")) {
                Optional<String> status = repo.getConsent(
                    "user-1",
                    null,
                    field,
                    "marketing"
                );
                assertTrue(status.isPresent(), "Missing entry for " + field);

                long ttl = repo.ttl("user-1", null, field, "marketing");
                assertTrue(
                    ttl > 7000 && ttl <= 7200,
                    "TTL for " + field + ": " + ttl
                );
            }
        }

        @Test
        @DisplayName("empty fields set is a no-op")
        void emptyFieldSet() {
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of()
            );
            // No exception, no keys written
        }

        @Test
        @DisplayName("null fields set is a no-op")
        void nullFieldSet() {
            repo.setCategoryConsentForFields("user-1", "marketing", true, null);
            // No exception, no keys written
        }

        @Test
        @DisplayName("past expiresAt skips all writes")
        void fanOutPastExpiry() {
            Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                past,
                Set.of("_default_/ssn", "_default_/email")
            );

            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertFalse(repo.exists("user-1", null, "email", "marketing"));
        }

        @Test
        @DisplayName("null expiresAt delegates to no-TTL overload")
        void fanOutNullExpiry() {
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                null,
                Set.of("_default_/ssn")
            );

            assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
            assertEquals(-1, repo.ttl("user-1", null, "ssn", "marketing"));
        }
    }

    // -----------------------------------------------------------------------
    // setCategoryConsentForFields — field-level precedence
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setCategoryConsentForFields — field-level precedence")
    class CategoryFanOutPrecedence {

        @Test
        @DisplayName("skips field with existing FIELD-sourced entry")
        void skipsFieldSourcedEntry() {
            // Pre-set an explicit field-level GRANTED for "ssn"
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            // Category fan-out tries to REVOKE across ssn + email
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn", "_default_/email")
            );

            // "ssn" must still be GRANTED|FIELD — not overwritten
            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                ssn.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                ssn.get().source()
            );

            // "email" had no prior entry → written as REVOKED|CATEGORY
            Optional<ConsentValue> email = repo.getConsentEntry(
                "user-1",
                null,
                "email",
                "marketing"
            );
            assertTrue(email.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                email.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                email.get().source()
            );
        }

        @Test
        @DisplayName("overwrites existing CATEGORY-sourced entry")
        void overwritesCategorySourcedEntry() {
            // Pre-set a CATEGORY-sourced GRANTED for "ssn"
            repo.setConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );

            // Category fan-out revokes the same field
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn")
            );

            // Should be overwritten to REVOKED|CATEGORY
            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                ssn.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                ssn.get().source()
            );
        }

        @Test
        @DisplayName("writes to fields with no existing entry")
        void writesNewFields() {
            // No pre-existing keys — all fields should be written
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
            );

            for (String field : Set.of("ssn", "email", "phone")) {
                Optional<ConsentValue> entry = repo.getConsentEntry(
                    "user-1",
                    null,
                    field,
                    "marketing"
                );
                assertTrue(entry.isPresent(), "Missing entry for " + field);
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    entry.get().status()
                );
                assertEquals(
                    ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                    entry.get().source()
                );
            }
        }

        @Test
        @DisplayName("mixed: new, CATEGORY-sourced, and FIELD-sourced")
        void mixedPrecedence() {
            // "ssn" → FIELD-sourced (should be preserved)
            repo.setFieldConsent("user-1", null, "ssn", "marketing", false);
            // "email" → CATEGORY-sourced (should be overwritten)
            repo.setConsent(
                "user-1",
                null,
                "email",
                "marketing",
                false,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );
            // "phone" → no entry (should be created)

            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                true,
                Set.of("_default_/ssn", "_default_/email", "_default_/phone")
            );

            // ssn: still REVOKED|FIELD — untouched
            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                ssn.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                ssn.get().source()
            );

            // email: overwritten to GRANTED|CATEGORY
            Optional<ConsentValue> email = repo.getConsentEntry(
                "user-1",
                null,
                "email",
                "marketing"
            );
            assertTrue(email.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                email.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                email.get().source()
            );

            // phone: new GRANTED|CATEGORY
            Optional<ConsentValue> phone = repo.getConsentEntry(
                "user-1",
                null,
                "phone",
                "marketing"
            );
            assertTrue(phone.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                phone.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                phone.get().source()
            );
        }

        @Test
        @DisplayName("all fields FIELD-sourced — entire fan-out is a no-op")
        void allFieldSourcedIsNoop() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-1", null, "email", "marketing", false);

            // Attempt to overwrite both with category REVOKED
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                Set.of("_default_/ssn", "_default_/email")
            );

            // Both must be unchanged
            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                ssn.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                ssn.get().source()
            );

            Optional<ConsentValue> email = repo.getConsentEntry(
                "user-1",
                null,
                "email",
                "marketing"
            );
            assertTrue(email.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                email.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                email.get().source()
            );
        }

        @Test
        @DisplayName("TTL overload also respects field-level precedence")
        void ttlOverloadRespectsPrecedence() {
            // Pre-set ssn as FIELD-sourced
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            Instant expiresAt = Instant.now().plus(2, ChronoUnit.HOURS);
            repo.setCategoryConsentForFields(
                "user-1",
                "marketing",
                false,
                expiresAt,
                Set.of("_default_/ssn", "_default_/email")
            );

            // ssn: still GRANTED|FIELD, no TTL change
            Optional<ConsentValue> ssn = repo.getConsentEntry(
                "user-1",
                null,
                "ssn",
                "marketing"
            );
            assertTrue(ssn.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                ssn.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_FIELD,
                ssn.get().source()
            );
            // ssn was set without TTL — should still have no TTL
            assertEquals(-1, repo.ttl("user-1", null, "ssn", "marketing"));

            // email: written as REVOKED|CATEGORY with TTL
            Optional<ConsentValue> email = repo.getConsentEntry(
                "user-1",
                null,
                "email",
                "marketing"
            );
            assertTrue(email.isPresent());
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                email.get().status()
            );
            assertEquals(
                ConsentPolicyRedisRepository.SOURCE_CATEGORY,
                email.get().source()
            );
            long ttl = repo.ttl("user-1", null, "email", "marketing");
            assertTrue(
                ttl > 7000 && ttl <= 7200,
                "Expected TTL ~7200, got: " + ttl
            );
        }
    }

    // -----------------------------------------------------------------------
    // removeConsent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeConsent")
    class RemoveConsent {

        @Test
        @DisplayName("removes an existing key")
        void removeExisting() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            assertTrue(repo.exists("user-1", null, "ssn", "marketing"));

            repo.removeConsent("user-1", null, "ssn", "marketing");
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("removing a non-existent key is a no-op")
        void removeNoOp() {
            repo.removeConsent("user-1", null, "ssn", "marketing");
            // No exception
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("remove only affects the targeted key")
        void removeIsolation() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-1", null, "email", "marketing", true);

            repo.removeConsent("user-1", null, "ssn", "marketing");

            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertTrue(repo.exists("user-1", null, "email", "marketing"));
        }
    }

    // -----------------------------------------------------------------------
    // evaluateScopes (MGET — the hot path)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evaluateScopes (MGET)")
    class EvaluateScopes {

        @Test
        @DisplayName("returns GRANTED for a field-sourced grant")
        void fieldGrant() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(1, result.size());
            DecisionDetail detail = result.get(scope);
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
        @DisplayName("returns GRANTED for a category-sourced grant")
        void categoryGrant() {
            repo.setConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                ConsentPolicyRedisRepository.SOURCE_CATEGORY
            );

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            DecisionDetail detail = result.get(scope);
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
        @DisplayName("returns REVOKED for a revoked entry")
        void revoked() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            DecisionDetail detail = result.get(scope);
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
        @DisplayName("returns null effectiveStatus when no key exists")
        void noRecord() {
            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            assertEquals(1, result.size());
            DecisionDetail detail = result.get(scope);
            assertNull(detail.effectiveStatus());
            assertEquals(DecisionDetail.RESOLVED_BY_NONE, detail.resolvedBy());
        }

        @Test
        @DisplayName("evaluates a mixed batch of granted, revoked, and missing")
        void mixedBatch() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-1", null, "email", "marketing", false);
            // phone has no entry

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "marketing");
            ConsentScope phone = ConsentScope.to("PII", "phone", "marketing");

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(ssn, email, phone)
            );

            assertEquals(3, result.size());
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(ssn).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                result.get(email).effectiveStatus()
            );
            assertNull(result.get(phone).effectiveStatus());
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
        @DisplayName("different purposes are isolated")
        void purposeIsolation() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
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

        @Test
        @DisplayName(
            "category field in scope is ignored — only field+purpose key matters"
        )
        void categoryInScopeIgnored() {
            // The key is operio:consent:user-1:ssn:marketing regardless
            // of whether the scope says category "PII" or "Financial"
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            ConsentScope pii = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope fin = ConsentScope.to("Financial", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(pii, fin)
            );

            // Both scopes resolve the same key, so both see GRANTED
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(pii).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                result.get(fin).effectiveStatus()
            );
        }

        @Test
        @DisplayName("source tag is reflected in resolvedBy")
        void sourceTagReflected() {
            repo.setConsent("user-1", null, "ssn", "marketing", true, "FIELD");
            repo.setConsent(
                "user-1",
                null,
                "email",
                "marketing",
                true,
                "CATEGORY"
            );

            ConsentScope ssn = ConsentScope.to("PII", "ssn", "marketing");
            ConsentScope email = ConsentScope.to("PII", "email", "marketing");

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(ssn, email)
            );

            assertEquals("FIELD", result.get(ssn).resolvedBy());
            assertEquals("CATEGORY", result.get(email).resolvedBy());
        }

        @Test
        @DisplayName("expiresAt is always null from Redis path")
        void expiresAtAlwaysNull() {
            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            repo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                expiresAt
            );

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );

            // DecisionDetail.expiresAt is null — Redis handles TTL natively
            assertNull(result.get(scope).expiresAt());
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
        void noLeak() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);

            assertTrue(
                repo.getConsent("user-1", null, "ssn", "marketing").isPresent()
            );
            assertTrue(
                repo.getConsent("user-2", null, "ssn", "marketing").isEmpty()
            );
        }

        @Test
        @DisplayName("evaluateScopes isolates per subject")
        void evaluateIsolation() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-2", null, "ssn", "marketing", false);

            ConsentScope scope = ConsentScope.to("PII", "ssn", "marketing");

            Map<ConsentScope, DecisionDetail> r1 = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );
            Map<ConsentScope, DecisionDetail> r2 = repo.evaluateScopes(
                "user-2",
                Set.of(scope)
            );
            Map<ConsentScope, DecisionDetail> r3 = repo.evaluateScopes(
                "user-3",
                Set.of(scope)
            );

            assertEquals(
                ConsentPolicyRedisRepository.GRANTED,
                r1.get(scope).effectiveStatus()
            );
            assertEquals(
                ConsentPolicyRedisRepository.REVOKED,
                r2.get(scope).effectiveStatus()
            );
            assertNull(r3.get(scope).effectiveStatus());
        }
    }

    // -----------------------------------------------------------------------
    // exists
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("exists")
    class Exists {

        @Test
        @DisplayName("returns true when key is present")
        void existsTrue() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("returns false when key is absent")
        void existsFalse() {
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }
    }

    // -----------------------------------------------------------------------
    // ttl
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ttl")
    class Ttl {

        @Test
        @DisplayName("returns -1 for a key with no TTL")
        void noTtl() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            assertEquals(-1, repo.ttl("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("returns -2 for a non-existent key")
        void missingKey() {
            assertEquals(-2, repo.ttl("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("returns positive value for a key with TTL")
        void positiveTtl() {
            Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
            repo.setFieldConsent(
                "user-1",
                null,
                "ssn",
                "marketing",
                true,
                expiresAt
            );

            long ttl = repo.ttl("user-1", null, "ssn", "marketing");
            assertTrue(
                ttl > 1700 && ttl <= 1800,
                "Expected ~1800, got: " + ttl
            );
        }
    }

    // -----------------------------------------------------------------------
    // removeAllConsent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeAllConsent")
    class RemoveAllConsent {

        @Test
        @DisplayName("removes all keys for a subject")
        void removesAll() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-1", null, "email", "analytics", false);
            repo.setFieldConsent("user-1", null, "phone", "marketing", true);

            long removed = repo.removeAllConsent("user-1");

            assertEquals(3, removed);
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertFalse(repo.exists("user-1", null, "email", "analytics"));
            assertFalse(repo.exists("user-1", null, "phone", "marketing"));
        }

        @Test
        @DisplayName("returns 0 for a subject with no consent")
        void noConsent() {
            long removed = repo.removeAllConsent("user-1");
            assertEquals(0, removed);
        }

        @Test
        @DisplayName("does not affect other subjects")
        void isolation() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            repo.setFieldConsent("user-2", null, "ssn", "marketing", true);

            repo.removeAllConsent("user-1");

            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertTrue(repo.exists("user-2", null, "ssn", "marketing"));
        }
    }

    // -----------------------------------------------------------------------
    // Large batch
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Large batch")
    class LargeBatch {

        @Test
        @DisplayName("evaluates 100 scopes in a single MGET")
        void hundredScopes() {
            // Write 50 field-level and 50 category-sourced entries
            for (int i = 0; i < 50; i++) {
                repo.setFieldConsent(
                    "user-1",
                    null,
                    "field_" + i,
                    "marketing",
                    true
                );
            }
            repo.setCategoryConsentForFields(
                "user-1",
                "analytics",
                true,
                buildQualifiedFieldSet(50, 100)
            );

            // Build 100 scopes
            Set<ConsentScope> scopes = new java.util.HashSet<>();
            for (int i = 0; i < 50; i++) {
                scopes.add(ConsentScope.to("PII", "field_" + i, "marketing"));
            }
            for (int i = 50; i < 100; i++) {
                scopes.add(ConsentScope.to("PII", "field_" + i, "analytics"));
            }

            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                scopes
            );

            assertEquals(100, result.size());
            for (DecisionDetail detail : result.values()) {
                assertEquals(
                    ConsentPolicyRedisRepository.GRANTED,
                    detail.effectiveStatus()
                );
            }
        }

        private Set<String> buildQualifiedFieldSet(int from, int to) {
            Set<String> fields = new java.util.HashSet<>();
            for (int i = from; i < to; i++) {
                fields.add("_default_/field_" + i);
            }
            return fields;
        }
    }

    // -----------------------------------------------------------------------
    // Test isolation verification
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Tenant-aware keys (Task 2.6)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant-aware keys")
    class TenantAwareKeys {

        @Test
        @DisplayName("keyPrefix includes tenantId when TenantContext is active")
        void keyPrefixWithTenant() throws Exception {
            TenantContext.callWhere("acme", () -> {
                assertEquals("operio:acme", repo.keyPrefix());
                return null;
            });
        }

        @Test
        @DisplayName(
            "keyPrefix is just appName when no TenantContext is active"
        )
        void keyPrefixWithoutTenant() {
            assertEquals("operio", repo.keyPrefix());
        }

        @Test
        @DisplayName(
            "consentKey includes tenant segment when TenantContext is active"
        )
        void consentKeyWithTenant() throws Exception {
            TenantContext.callWhere("acme", () -> {
                assertEquals(
                    "operio:acme:consent:user-1:_default_/ssn:marketing",
                    repo.consentKey("user-1", null, "ssn", "marketing")
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
                "operio:consent:user-1:_default_/ssn:marketing",
                repo.consentKey("user-1", null, "ssn", "marketing")
            );
        }

        @Test
        @DisplayName(
            "write and read within a tenant context are isolated from default namespace"
        )
        void tenantWriteReadIsolation() throws Exception {
            // Write in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
                return null;
            });

            // Read outside tenant context — should NOT see acme's data
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertTrue(
                repo.getConsent("user-1", null, "ssn", "marketing").isEmpty()
            );

            // Read inside tenant "acme" — should see the data
            TenantContext.callWhere("acme", () -> {
                assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
                Optional<String> status = repo.getConsent(
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
                repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
                return null;
            });

            // Tenant "globex" revokes same scope
            TenantContext.callWhere("globex", () -> {
                repo.setFieldConsent("user-1", null, "ssn", "marketing", false);
                return null;
            });

            // Each tenant sees only its own decision
            TenantContext.callWhere("acme", () -> {
                Optional<String> status = repo.getConsent(
                    "user-1",
                    null,
                    "ssn",
                    "marketing"
                );
                assertEquals("GRANTED", status.orElse(null));
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                Optional<String> status = repo.getConsent(
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
            ConsentScope scope = new ConsentScope(
                null,
                "ssn",
                null,
                "marketing"
            );

            // Grant in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
                return null;
            });

            // Evaluate inside "acme" — should be granted
            TenantContext.callWhere("acme", () -> {
                Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                    "user-1",
                    Set.of(scope)
                );
                DecisionDetail detail = result.get(scope);
                assertNotNull(detail);
                assertEquals("GRANTED", detail.effectiveStatus());
                return null;
            });

            // Evaluate inside "globex" — no record
            TenantContext.callWhere("globex", () -> {
                Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                    "user-1",
                    Set.of(scope)
                );
                DecisionDetail detail = result.get(scope);
                assertNotNull(detail);
                assertNull(detail.effectiveStatus());
                return null;
            });

            // Evaluate outside tenant context — no record
            Map<ConsentScope, DecisionDetail> result = repo.evaluateScopes(
                "user-1",
                Set.of(scope)
            );
            DecisionDetail detail = result.get(scope);
            assertNotNull(detail);
            assertNull(detail.effectiveStatus());
        }

        @Test
        @DisplayName("removeAllConsent only removes keys for the active tenant")
        void removeAllConsentTenantIsolation() throws Exception {
            // Write data in two tenants
            TenantContext.callWhere("acme", () -> {
                repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
                repo.setFieldConsent(
                    "user-1",
                    null,
                    "email",
                    "marketing",
                    true
                );
                return null;
            });

            TenantContext.callWhere("globex", () -> {
                repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
                return null;
            });

            // Remove all for user-1 in "acme" only
            TenantContext.callWhere("acme", () -> {
                long removed = repo.removeAllConsent("user-1");
                assertEquals(2, removed);
                return null;
            });

            // "acme" data is gone
            TenantContext.callWhere("acme", () -> {
                assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
                assertFalse(repo.exists("user-1", null, "email", "marketing"));
                return null;
            });

            // "globex" data is untouched
            TenantContext.callWhere("globex", () -> {
                assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
                return null;
            });
        }

        @Test
        @DisplayName("category fan-out writes are tenant-scoped")
        void categoryFanOutWithTenant() throws Exception {
            Set<String> fields = Set.of("_default_/ssn", "_default_/email");

            // Fan out in tenant "acme"
            TenantContext.callWhere("acme", () -> {
                repo.setCategoryConsentForFields(
                    "user-1",
                    "marketing",
                    true,
                    fields
                );
                return null;
            });

            // Verify keys exist in "acme"
            TenantContext.callWhere("acme", () -> {
                assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
                assertTrue(repo.exists("user-1", null, "email", "marketing"));
                return null;
            });

            // Verify keys do NOT exist outside tenant context
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
            assertFalse(repo.exists("user-1", null, "email", "marketing"));

            // Verify keys do NOT exist in a different tenant
            TenantContext.callWhere("globex", () -> {
                assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
                assertFalse(repo.exists("user-1", null, "email", "marketing"));
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
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            repo.setFieldConsent("user-1", null, "ssn", "marketing", true);
            assertTrue(repo.exists("user-1", null, "ssn", "marketing"));
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            assertFalse(repo.exists("user-1", null, "ssn", "marketing"));
        }
    }
}
