package com.koostack.operio.core;

import com.koostack.operio.core.Models.ConsentAuditEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConsentAuditEntry} — the immutable record that captures
 * a single consent lifecycle event (grant, revoke, expire, etc.) for audit
 * trail purposes.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Construction with all fields populated</li>
 *   <li>Field accessor correctness</li>
 *   <li>Nullable fields (expiresAt, ipAddress, userAgent, metadata)</li>
 *   <li>Record semantics (equals, hashCode, toString)</li>
 *   <li>Metadata map contents</li>
 *   <li>Action string conventions (GRANTED, REVOKED, etc.)</li>
 * </ul>
 */
@DisplayName("ConsentAuditEntry (record)")
class ConsentAuditEntryTest {

    private static final Instant NOW = Instant.now();
    private static final Instant FUTURE = NOW.plus(90, ChronoUnit.DAYS);

    private static ConsentAuditEntry fullEntry() {
        return new ConsentAuditEntry(
            "user-123",
            "email",
            "user",
            "pii",
            "marketing",
            "GRANTED",
            FUTURE,
            "admin@acme.com",
            "10.0.0.1",
            "Mozilla/5.0",
            Map.of("source", "dashboard", "request_id", "req-abc"),
            NOW
        );
    }

    // -----------------------------------------------------------------------
    // Construction and field accessors
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Construction and field accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("all fields are accessible after construction")
        void allFieldsAccessible() {
            ConsentAuditEntry entry = fullEntry();

            assertEquals("user-123", entry.subject());
            assertEquals("email", entry.fieldName());
            assertEquals("user", entry.entityName());
            assertEquals("pii", entry.categoryName());
            assertEquals("marketing", entry.purposeName());
            assertEquals("GRANTED", entry.action());
            assertEquals(FUTURE, entry.expiresAt());
            assertEquals("admin@acme.com", entry.actor());
            assertEquals("10.0.0.1", entry.ipAddress());
            assertEquals("Mozilla/5.0", entry.userAgent());
            assertEquals(Map.of("source", "dashboard", "request_id", "req-abc"), entry.metadata());
            assertEquals(NOW, entry.timestamp());
        }

        @Test
        @DisplayName("subject identifies the data subject")
        void subjectIdentifiesDataSubject() {
            ConsentAuditEntry entry = fullEntry();
            assertEquals("user-123", entry.subject());
        }

        @Test
        @DisplayName("scope fields (fieldName, entityName, categoryName, purposeName) are correct")
        void scopeFieldsAreCorrect() {
            ConsentAuditEntry entry = fullEntry();

            assertEquals("email", entry.fieldName());
            assertEquals("user", entry.entityName());
            assertEquals("pii", entry.categoryName());
            assertEquals("marketing", entry.purposeName());
        }

        @Test
        @DisplayName("actor captures who performed the action")
        void actorCapturesWhoPerformed() {
            ConsentAuditEntry entry = fullEntry();
            assertEquals("admin@acme.com", entry.actor());
        }

        @Test
        @DisplayName("timestamp captures when the event occurred")
        void timestampCapturesWhen() {
            ConsentAuditEntry entry = fullEntry();
            assertEquals(NOW, entry.timestamp());
        }
    }

    // -----------------------------------------------------------------------
    // Nullable fields
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Nullable fields")
    class NullableFields {

        @Test
        @DisplayName("expiresAt can be null (no expiration)")
        void expiresAtCanBeNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", "marketing",
                "GRANTED", null, "admin", "10.0.0.1", "curl", Map.of(), NOW
            );

            assertNull(entry.expiresAt());
        }

        @Test
        @DisplayName("ipAddress can be null (e.g. programmatic call)")
        void ipAddressCanBeNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", null,
                "GRANTED", FUTURE, "system", null, null, Map.of(), NOW
            );

            assertNull(entry.ipAddress());
        }

        @Test
        @DisplayName("userAgent can be null")
        void userAgentCanBeNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", null,
                "GRANTED", FUTURE, "system", "127.0.0.1", null, Map.of(), NOW
            );

            assertNull(entry.userAgent());
        }

        @Test
        @DisplayName("purposeName can be null (consent without purpose)")
        void purposeNameCanBeNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", null,
                "REVOKED", null, "system", null, null, Map.of(), NOW
            );

            assertNull(entry.purposeName());
        }

        @Test
        @DisplayName("metadata can be null")
        void metadataCanBeNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", "analytics",
                "GRANTED", FUTURE, "admin", "10.0.0.1", "curl", null, NOW
            );

            assertNull(entry.metadata());
        }

        @Test
        @DisplayName("all nullable fields null simultaneously")
        void allNullableFieldsNull() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "user-123", "email", "user", "pii", null,
                "GRANTED", null, "system", null, null, null, NOW
            );

            assertNull(entry.purposeName());
            assertNull(entry.expiresAt());
            assertNull(entry.ipAddress());
            assertNull(entry.userAgent());
            assertNull(entry.metadata());
            // Non-nullable fields still present
            assertEquals("user-123", entry.subject());
            assertEquals("email", entry.fieldName());
            assertEquals("GRANTED", entry.action());
            assertEquals("system", entry.actor());
            assertEquals(NOW, entry.timestamp());
        }
    }

    // -----------------------------------------------------------------------
    // Action string conventions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Action conventions")
    class ActionConventions {

        @Test
        @DisplayName("GRANTED action")
        void grantedAction() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "GRANTED", FUTURE, "admin", null, null, Map.of(), NOW
            );
            assertEquals("GRANTED", entry.action());
        }

        @Test
        @DisplayName("REVOKED action")
        void revokedAction() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "REVOKED", null, "admin", null, null, Map.of(), NOW
            );
            assertEquals("REVOKED", entry.action());
        }

        @Test
        @DisplayName("EXPIRED action")
        void expiredAction() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "EXPIRED", null, "system", null, null, Map.of(), NOW
            );
            assertEquals("EXPIRED", entry.action());
        }

        @Test
        @DisplayName("arbitrary action string is accepted (no enum enforcement)")
        void arbitraryActionAccepted() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "CUSTOM_ACTION", null, "system", null, null, Map.of(), NOW
            );
            assertEquals("CUSTOM_ACTION", entry.action());
        }
    }

    // -----------------------------------------------------------------------
    // Metadata map
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Metadata map")
    class MetadataMap {

        @Test
        @DisplayName("metadata preserves all key-value pairs")
        void metadataPreservesEntries() {
            Map<String, String> meta = Map.of(
                "source", "api",
                "request_id", "req-xyz-789",
                "consent_version", "v2"
            );

            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "GRANTED", FUTURE, "admin", null, null, meta, NOW
            );

            assertEquals(3, entry.metadata().size());
            assertEquals("api", entry.metadata().get("source"));
            assertEquals("req-xyz-789", entry.metadata().get("request_id"));
            assertEquals("v2", entry.metadata().get("consent_version"));
        }

        @Test
        @DisplayName("empty metadata map is allowed")
        void emptyMetadataAllowed() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotNull(entry.metadata());
            assertTrue(entry.metadata().isEmpty());
        }

        @Test
        @DisplayName("metadata from Map.of() is unmodifiable")
        void metadataFromMapOfIsUnmodifiable() {
            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", "p1",
                "GRANTED", null, "admin", null, null, Map.of("k", "v"), NOW
            );

            assertThrows(UnsupportedOperationException.class, () ->
                entry.metadata().put("injected", "value")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Record semantics (equals, hashCode, toString)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Record semantics")
    class RecordSemantics {

        @Test
        @DisplayName("equals: identical entries are equal")
        void identicalEntriesAreEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "marketing",
                "GRANTED", FUTURE, "admin", "10.0.0.1", "curl", Map.of("k", "v"), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "marketing",
                "GRANTED", FUTURE, "admin", "10.0.0.1", "curl", Map.of("k", "v"), NOW
            );

            assertEquals(a, b);
        }

        @Test
        @DisplayName("hashCode: identical entries produce same hash")
        void identicalEntriesProduceSameHash() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "marketing",
                "GRANTED", FUTURE, "admin", "10.0.0.1", "curl", Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "marketing",
                "GRANTED", FUTURE, "admin", "10.0.0.1", "curl", Map.of(), NOW
            );

            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("equals: different subject → not equal")
        void differentSubjectNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u2", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different action → not equal")
        void differentActionNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "REVOKED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different timestamp → not equal")
        void differentTimestampNotEqual() {
            Instant later = NOW.plus(1, ChronoUnit.HOURS);

            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), later
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different fieldName → not equal")
        void differentFieldNameNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "phone", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different entityName → not equal")
        void differentEntityNameNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "account", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different categoryName → not equal")
        void differentCategoryNameNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "contact", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different purposeName → not equal")
        void differentPurposeNameNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "marketing",
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", "analytics",
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different actor → not equal")
        void differentActorNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of(), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "system", null, null, Map.of(), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different metadata → not equal")
        void differentMetadataNotEqual() {
            ConsentAuditEntry a = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of("k", "v1"), NOW
            );
            ConsentAuditEntry b = new ConsentAuditEntry(
                "u1", "email", "user", "pii", null,
                "GRANTED", null, "admin", null, null, Map.of("k", "v2"), NOW
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("not equal to null or different type")
        void notEqualToNullOrDifferentType() {
            ConsentAuditEntry entry = fullEntry();

            assertNotEquals(null, entry);
            assertNotEquals("not-an-audit-entry", entry);
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringIncludesKeyFields() {
            ConsentAuditEntry entry = fullEntry();
            String s = entry.toString();

            assertTrue(s.contains("user-123"), "toString should contain subject");
            assertTrue(s.contains("email"), "toString should contain fieldName");
            assertTrue(s.contains("GRANTED"), "toString should contain action");
            assertTrue(s.contains("admin@acme.com"), "toString should contain actor");
        }
    }

    // -----------------------------------------------------------------------
    // Temporal correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Temporal correctness")
    class TemporalCorrectness {

        @Test
        @DisplayName("expiresAt is after timestamp for future expiration")
        void expiresAtAfterTimestamp() {
            ConsentAuditEntry entry = fullEntry();

            assertTrue(entry.expiresAt().isAfter(entry.timestamp()),
                "expiresAt should be after timestamp for a future expiration");
        }

        @Test
        @DisplayName("timestamp precision is preserved (nanoseconds)")
        void timestampPrecisionPreserved() {
            Instant precise = Instant.parse("2025-06-15T12:34:56.789012345Z");

            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", null,
                "GRANTED", null, "admin", null, null, Map.of(), precise
            );

            assertEquals(precise, entry.timestamp());
            assertEquals(789012345, entry.timestamp().getNano());
        }

        @Test
        @DisplayName("expiresAt precision is preserved")
        void expiresAtPrecisionPreserved() {
            Instant precise = Instant.parse("2026-12-31T23:59:59.999999999Z");

            ConsentAuditEntry entry = new ConsentAuditEntry(
                "u1", "f1", "e1", "c1", null,
                "GRANTED", precise, "admin", null, null, Map.of(), NOW
            );

            assertEquals(precise, entry.expiresAt());
        }
    }
}
