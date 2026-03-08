package com.koostack.operio.tenant.event.jooq;

import static com.koostack.operio.tenant.event.jooq.generated.tables.Event.EVENT;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectAggregate;
import com.koostack.operio.core.tenant.consent.events.SubjectRegisteredEvent;
import com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for the Digital Shredder pattern:
 *
 * <ol>
 *   <li>Shreddable event payloads are encrypted at publish time
 *   <li>OutboxPoller decrypts before Redis publish
 *   <li>Deleting the DSAR key permanently shreds events
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DigitalShredderTest extends TenantEventServiceTestBase {

    private static final String ADMIN_KEY = "test-admin-encryption-key-12345";

    private static InMemorySubjectDsarService dsarService;

    @BeforeAll
    static void setUp() {
        dsarService = new InMemorySubjectDsarService();
        service.setSubjectDsarService(dsarService, ADMIN_KEY);
    }

    // -----------------------------------------------------------------------
    // 1. Shreddable events are encrypted at rest
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void shreddableEventPayloadIsEncryptedInDatabase() throws Exception {
        String subjectName = "shred-test-" + UUID.randomUUID();
        String rawKey = generateRawDsarKey();
        String encryptedKey = FieldEncryptor.encrypt(rawKey, ADMIN_KEY);
        UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        dsarService.saveKey(dsarId, encryptedKey);

        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new SubjectRegisteredEvent(subjectName, "test description");
        service.publishEvent(
                PublishDomainEventInput.of(
                        TEST_TENANT, ConsentEventTypes.SUBJECT_REGISTERED, key, payload));

        var record =
                dsl.selectFrom(EVENT)
                        .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
                        .fetchOne();

        assertNotNull(record, "Event should be in the database");
        assertTrue(record.getShreddable(), "Event should be marked shreddable");
        assertEquals(dsarId, record.getDsarId(), "Event should carry the dsar_id");

        // Payload should NOT be plaintext JSON
        String storedPayload = record.getPayload();
        assertFalse(
                storedPayload.contains(subjectName),
                "Encrypted payload should not contain the subject name in plaintext");

        // Unwrap the JSON string scalar, then decrypt and verify
        String ciphertext =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(storedPayload, String.class);
        String decryptedPayload = FieldEncryptor.decrypt(ciphertext, rawKey);
        assertTrue(
                decryptedPayload.contains(subjectName),
                "Decrypted payload should contain the subject name");
        assertTrue(
                decryptedPayload.contains("test description"),
                "Decrypted payload should contain the description");
    }

    // -----------------------------------------------------------------------
    // 2. OutboxPoller decrypts for Redis (projections get plaintext)
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    void outboxDecryptsShreddablePayloadBeforeRedisPublish() throws Exception {
        int beforeCount = consumedEvents.size();

        String subjectName = "shred-redis-" + UUID.randomUUID();
        String rawKey = generateRawDsarKey();
        String encryptedKey = FieldEncryptor.encrypt(rawKey, ADMIN_KEY);
        UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        dsarService.saveKey(dsarId, encryptedKey);

        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new SubjectRegisteredEvent(subjectName, "redis decrypt test");
        service.publishEvent(
                PublishDomainEventInput.of(
                        TEST_TENANT, ConsentEventTypes.SUBJECT_REGISTERED, key, payload));

        awaitConsumedEvents(beforeCount + 1, 6000);

        var matching =
                consumedEvents.stream()
                        .filter(
                                e ->
                                        aggregateId
                                                .toString()
                                                .equals(e.get(TenantEvent.FIELD_AGGREGATE_ID)))
                        .toList();

        assertFalse(matching.isEmpty(), "Event should appear in Redis stream");

        Map<String, String> event = matching.getFirst();
        String redisPayload = event.get(TenantEvent.FIELD_PAYLOAD);
        assertNotNull(redisPayload, "Redis event should have a payload");
        assertTrue(
                redisPayload.contains(subjectName),
                "Redis payload should contain plaintext subject name (OutboxPoller decrypted)");
    }

    // -----------------------------------------------------------------------
    // 3. Shredding: delete key → events become unreadable
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    void shredSubjectRendersEventsUnreadable() throws Exception {
        String subjectName = "shred-delete-" + UUID.randomUUID();
        String rawKey = generateRawDsarKey();
        String encryptedKey = FieldEncryptor.encrypt(rawKey, ADMIN_KEY);
        UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        dsarService.saveKey(dsarId, encryptedKey);

        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new SubjectRegisteredEvent(subjectName, "will be shredded");
        service.publishEvent(
                PublishDomainEventInput.of(
                        TEST_TENANT, ConsentEventTypes.SUBJECT_REGISTERED, key, payload));

        // Verify event exists and is encrypted
        var record =
                dsl.selectFrom(EVENT)
                        .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
                        .fetchOne();
        assertNotNull(record);
        assertTrue(record.getShreddable());

        // Shred: delete the key
        dsarService.deleteKey(dsarId);

        // Key is gone
        assertTrue(dsarService.findKeyByDsarId(dsarId).isEmpty());

        // Payload in DB is still there but can't be decrypted without the key
        String storedPayload = record.getPayload();
        assertNotNull(storedPayload, "Encrypted payload should still exist in DB");
        assertFalse(
                storedPayload.contains(subjectName),
                "Payload should remain encrypted (subject name not visible)");
    }

    // -----------------------------------------------------------------------
    // 4. Non-shreddable events remain plaintext
    // -----------------------------------------------------------------------

    @Test
    @Order(4)
    void nonShreddableEventRemainsPlaintext() {
        String name = "non-shred-regulation-" + UUID.randomUUID();
        UUID aggregateId =
                com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate.deriveId(
                        name);
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate
                                .AGGREGATE_TYPE,
                        TEST_TENANT);
        var payload =
                new com.koostack.operio.core.tenant.consent.events.RegulationRegisteredEvent(
                        name, "plaintext description");
        service.publishEvent(
                PublishDomainEventInput.of(
                        TEST_TENANT, ConsentEventTypes.REGULATION_REGISTERED, key, payload));

        var record =
                dsl.selectFrom(EVENT)
                        .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
                        .fetchOne();

        assertNotNull(record);
        assertFalse(record.getShreddable(), "Non-shreddable event should not be marked");
        assertNull(record.getDsarId(), "Non-shreddable event should not have dsar_id");
        assertTrue(
                record.getPayload().contains(name), "Non-shreddable payload should be plaintext");
    }

    // -----------------------------------------------------------------------
    // 5. Publishing fails if DSAR key is missing (subject not registered)
    // -----------------------------------------------------------------------

    @Test
    @Order(5)
    void publishFailsWhenDsarKeyMissing() {
        String subjectName = "no-key-subject-" + UUID.randomUUID();
        // Intentionally NOT saving a DSAR key

        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new SubjectRegisteredEvent(subjectName, "should fail");

        assertThrows(
                OperioServiceException.class,
                () ->
                        service.publishEvent(
                                PublishDomainEventInput.of(
                                        TEST_TENANT,
                                        ConsentEventTypes.SUBJECT_REGISTERED,
                                        key,
                                        payload)),
                "Should throw when DSAR key is not found for a shreddable event");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String generateRawDsarKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            return Base64.getEncoder().encodeToString(gen.generateKey().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Simple in-memory DSAR service for testing — avoids needing the consent-admin schema in the
     * tenant-event test database.
     */
    static class InMemorySubjectDsarService implements SubjectDsarService<Void> {

        private final ConcurrentHashMap<UUID, String> keys = new ConcurrentHashMap<>();

        @Override
        public Class<Void> getConfigurationType() {
            return Void.class;
        }

        @Override
        public void configure(Void config) {
            /* no-op */
        }

        @Override
        public Optional<String> findKeyByDsarId(UUID dsarId) {
            return Optional.ofNullable(keys.get(dsarId));
        }

        @Override
        public void saveKey(UUID dsarId, String encryptedKey) {
            keys.put(dsarId, encryptedKey);
        }

        @Override
        public void deleteKey(UUID dsarId) {
            keys.remove(dsarId);
        }

        @Override
        public boolean allEventsPublished(UUID dsarId) {
            return true;
        }
    }
}
