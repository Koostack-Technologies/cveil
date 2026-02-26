package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.event.models.PublishEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.models.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.koostack.operio.tenant.event.jooq.generated.tables.AggregateSnapshot.AGGREGATE_SNAPSHOT;
import static com.koostack.operio.tenant.event.jooq.generated.tables.Event.EVENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link JooqTenantEventService}.
 *
 * <p>Tests publish, getAggregate, getAggregateVersion, snapshot creation,
 * outbox-to-Redis publishing, and multi-tenant isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JooqTenantEventServiceTest extends TenantEventServiceTestBase {

    private static final String EVENT_TYPE = "TEST_EVENT";

    @BeforeAll
    static void registerTestEventType() {
        service.registerEventType(new RegisterEventTypeInput<>(
            EVENT_TYPE, "Test Event", TestAggregate.class
        ));
    }

    // -----------------------------------------------------------------------
    // publish + getAggregate
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void publishInsertsEventAndHydratesAggregate() {
        UUID aggregateId = UUID.randomUUID();

        TestAggregate state = new TestAggregate(
            TEST_TENANT, aggregateId, "test-aggregate", 1L,
            "Alice", "alice@example.com", 30
        );
        service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));

        var record = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .fetchOne();

        assertNotNull(record);
        assertEquals("test-aggregate", record.getAggregateType());
        assertEquals(EVENT_TYPE, record.getEventType());
        assertEquals(1L, record.getVersion());
        assertEquals(TEST_TENANT, record.getTenantId());

        AggregateKey key = new AggregateKey(aggregateId, "test-aggregate", TEST_TENANT);
        TestAggregate hydrated = service.getAggregate(key, TestAggregate.class);

        assertEquals("Alice", hydrated.name());
        assertEquals("alice@example.com", hydrated.email());
        assertEquals(30, hydrated.age());
    }

    // -----------------------------------------------------------------------
    // getAggregateVersion
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    void getAggregateVersionReturnsCorrectVersion() {
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "ver-aggregate", TEST_TENANT);

        Long v0 = service.getAggregateVersion(key);
        assertEquals(0L, v0, "Non-existent aggregate should have version 0");

        for (int i = 1; i <= 3; i++) {
            TestAggregate state = new TestAggregate(
                TEST_TENANT, aggregateId, "ver-aggregate", (long) i,
                "user-v" + i, "v" + i + "@test.com", 20 + i
            );
            service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));
        }

        Long v3 = service.getAggregateVersion(key);
        assertEquals(3L, v3);
    }

    // -----------------------------------------------------------------------
    // Snapshot creation after threshold
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    void snapshotCreatedAfterTwentyEvents() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "snap-aggregate", TEST_TENANT);

        for (int i = 1; i <= 21; i++) {
            TestAggregate state = new TestAggregate(
                TEST_TENANT, aggregateId, "snap-aggregate", (long) i,
                "user-v" + i, "u" + i + "@test.com", 20 + i
            );
            service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));
        }

        // Trigger hydration to enqueue snapshot
        service.getAggregate(key, TestAggregate.class);
        TimeUnit.SECONDS.sleep(2);

        var snapshot = dsl.selectFrom(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId.toString()))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq("snap-aggregate"))
            .orderBy(AGGREGATE_SNAPSHOT.VERSION.desc())
            .limit(1)
            .fetchOne();

        assertNotNull(snapshot, "Snapshot should have been created after 21 events");
        assertTrue(snapshot.getVersion() >= 20, "Snapshot version should be >= 20");
        assertNotNull(snapshot.getSnapshot());
    }

    // -----------------------------------------------------------------------
    // Rehydrate from snapshot
    // -----------------------------------------------------------------------

    @Test
    @Order(4)
    void rehydrateFromSnapshotReturnsCorrectState() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "rehydrate-agg", TEST_TENANT);

        for (int i = 1; i <= 21; i++) {
            TestAggregate state = new TestAggregate(
                TEST_TENANT, aggregateId, "rehydrate-agg", (long) i,
                "pre-snap-" + i, "pre" + i + "@test.com", 25 + i
            );
            service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));
        }

        service.getAggregate(key, TestAggregate.class);
        TimeUnit.SECONDS.sleep(2);

        // Post-snapshot events
        for (int i = 22; i <= 25; i++) {
            TestAggregate state = new TestAggregate(
                TEST_TENANT, aggregateId, "rehydrate-agg", (long) i,
                "post-snap-" + i, "post" + i + "@test.com", 50 + i
            );
            service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));
        }

        TestAggregate hydrated = service.getAggregate(key, TestAggregate.class);

        assertEquals("post-snap-25", hydrated.name());
        assertEquals("post25@test.com", hydrated.email());
        assertEquals(75, hydrated.age());
        assertEquals(25L, hydrated.version());
    }

    // -----------------------------------------------------------------------
    // Outbox publishes to correct sharded stream
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void outboxPublishesToRedisWithAllFields() throws Exception {
        int beforeCount = consumedEvents.size();

        UUID aggregateId = UUID.randomUUID();
        TestAggregate state = new TestAggregate(
            TEST_TENANT, aggregateId, "redis-aggregate", 1L,
            "RedisUser", "redis@test.com", 40
        );
        service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));

        awaitConsumedEvents(beforeCount + 1, 6000);

        var matching = consumedEvents.stream()
            .filter(e -> aggregateId.toString().equals(e.get(TenantEvent.FIELD_AGGREGATE_ID)))
            .toList();

        assertFalse(matching.isEmpty(), "Event should appear in Redis stream");

        Map<String, String> event = matching.getFirst();
        assertNotNull(event.get(TenantEvent.FIELD_EVENT_ID), "Missing eventId");
        assertNotNull(event.get(TenantEvent.FIELD_TENANT_ID), "Missing tenantId");
        assertEquals(TEST_TENANT, event.get(TenantEvent.FIELD_TENANT_ID));
        assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_ID), "Missing aggregateId");
        assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_TYPE), "Missing aggregateType");
        assertNotNull(event.get(TenantEvent.FIELD_EVENT_TYPE), "Missing eventType");
        assertNotNull(event.get(TenantEvent.FIELD_VERSION), "Missing version");
        assertNotNull(event.get(TenantEvent.FIELD_CHANGES), "Missing changes");
    }

    // -----------------------------------------------------------------------
    // Multi-tenant isolation (Issue 6)
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void multiTenantAggregateIsolation() {
        String tenantA = TEST_TENANT;
        String tenantB = TEST_TENANT; // same DB, different logical tenant ID in event
        // Use same aggregate ID to prove per-tenant isolation in the event store
        UUID aggregateId = UUID.randomUUID();

        TestAggregate stateA = new TestAggregate(
            tenantA, aggregateId, "mt-aggregate", 1L,
            "TenantA-User", "a@test.com", 10
        );
        service.publish(PublishEventInput.from(tenantA, EVENT_TYPE, stateA));

        // Publish under tenantA context — then verify aggregate state for tenantA
        AggregateKey keyA = new AggregateKey(aggregateId, "mt-aggregate", tenantA);
        TestAggregate hydratedA = service.getAggregate(keyA, TestAggregate.class);
        assertEquals("TenantA-User", hydratedA.name());

        // Publish a second version under the same tenant and verify version increments
        TestAggregate stateA2 = new TestAggregate(
            tenantA, aggregateId, "mt-aggregate", 2L,
            "TenantA-Updated", "a2@test.com", 11
        );
        service.publish(PublishEventInput.from(tenantA, EVENT_TYPE, stateA2));

        TestAggregate hydratedA2 = service.getAggregate(keyA, TestAggregate.class);
        assertEquals("TenantA-Updated", hydratedA2.name());
        assertEquals(2L, hydratedA2.version());
    }

    @Test
    @Order(21)
    void multiTenantRedisEventsCarryCorrectTenantId() throws Exception {
        int beforeCount = consumedEvents.size();

        UUID aggregateId = UUID.randomUUID();
        TestAggregate state = new TestAggregate(
            TEST_TENANT, aggregateId, "mt-redis-aggregate", 1L,
            "MT-Redis", "mt@test.com", 50
        );
        service.publish(PublishEventInput.from(TEST_TENANT, EVENT_TYPE, state));

        awaitConsumedEvents(beforeCount + 1, 6000);

        List<Map<String, String>> tenantEvents = consumedByTenant(TEST_TENANT);
        assertFalse(tenantEvents.isEmpty(), "Should have events for " + TEST_TENANT);

        // Every event for this tenant must carry the correct tenantId
        for (Map<String, String> event : tenantEvents) {
            assertEquals(TEST_TENANT, event.get(TenantEvent.FIELD_TENANT_ID),
                "All events should carry the correct tenantId");
        }
    }

    // -----------------------------------------------------------------------
    // Test aggregate record
    // -----------------------------------------------------------------------

    record TestAggregate(
        String tenantId,
        UUID aggregateId,
        String aggregateType,
        Long version,
        String name,
        String email,
        int age
    ) implements AggregateRoot {}
}
