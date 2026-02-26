package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.events.CategoryRegisteredEvent;
import com.koostack.operio.core.data.consent.events.DomainEvent;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateKey;
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
 * <p>Tests publishEvent, getAggregate, getAggregateVersion, snapshot creation,
 * outbox-to-Redis publishing, and multi-tenant isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JooqTenantEventServiceTest extends TenantEventServiceTestBase {

    @BeforeAll
    static void registerTestEventType() {
        service.registerEventType(new RegisterEventTypeInput<>(
            ConsentEventTypes.CATEGORY_REGISTERED, "Category Registered", CategoryRegisteredEvent.class
        ));
    }

    // -----------------------------------------------------------------------
    // publishEvent + getAggregate
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void publishEventInsertsEventAndHydratesAggregate() {
        String categoryName = "test-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);

        var payload = new CategoryRegisteredEvent(categoryName, "Test category description");
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);
        service.publishEvent(PublishDomainEventInput.of(
            TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
        ));

        var record = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .fetchOne();

        assertNotNull(record);
        assertEquals(CategoryAggregate.AGGREGATE_TYPE, record.getAggregateType());
        assertEquals(ConsentEventTypes.CATEGORY_REGISTERED, record.getEventType());
        assertEquals(1L, record.getVersion());
        assertEquals(TEST_TENANT, record.getTenantId());

        CategoryAggregate hydrated = service.getAggregate(key, CategoryAggregate.class);

        assertEquals(categoryName, hydrated.categoryName());
        assertEquals("Test category description", hydrated.description());
    }

    // -----------------------------------------------------------------------
    // getAggregateVersion
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    void getAggregateVersionReturnsCorrectVersion() {
        String categoryName = "ver-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);

        Long v0 = service.getAggregateVersion(key);
        assertEquals(0L, v0, "Non-existent aggregate should have version 0");

        for (int i = 1; i <= 3; i++) {
            var payload = new CategoryRegisteredEvent(categoryName, "description-v" + i);
            service.publishEvent(PublishDomainEventInput.of(
                TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
            ));
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
        String categoryName = "snap-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);

        for (int i = 1; i <= 21; i++) {
            var payload = new CategoryRegisteredEvent(categoryName, "snap-desc-v" + i);
            service.publishEvent(PublishDomainEventInput.of(
                TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
            ));
        }

        // Trigger hydration to enqueue snapshot
        service.getAggregate(key, CategoryAggregate.class);
        TimeUnit.SECONDS.sleep(2);

        var snapshot = dsl.selectFrom(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId.toString()))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq(CategoryAggregate.AGGREGATE_TYPE))
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
        String categoryName = "rehydrate-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);

        for (int i = 1; i <= 21; i++) {
            var payload = new CategoryRegisteredEvent(categoryName, "pre-snap-" + i);
            service.publishEvent(PublishDomainEventInput.of(
                TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
            ));
        }

        service.getAggregate(key, CategoryAggregate.class);
        TimeUnit.SECONDS.sleep(2);

        // Post-snapshot events
        for (int i = 22; i <= 25; i++) {
            var payload = new CategoryRegisteredEvent(categoryName, "post-snap-" + i);
            service.publishEvent(PublishDomainEventInput.of(
                TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
            ));
        }

        CategoryAggregate hydrated = service.getAggregate(key, CategoryAggregate.class);

        assertEquals(categoryName, hydrated.categoryName());
        assertEquals("post-snap-25", hydrated.description());
    }

    // -----------------------------------------------------------------------
    // Outbox publishes to correct sharded stream
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void outboxPublishesToRedisWithAllFields() throws Exception {
        int beforeCount = consumedEvents.size();

        String categoryName = "redis-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new CategoryRegisteredEvent(categoryName, "Redis test");
        service.publishEvent(PublishDomainEventInput.of(
            TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
        ));

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
        assertNotNull(event.get(TenantEvent.FIELD_PAYLOAD), "Missing payload");
    }

    // -----------------------------------------------------------------------
    // Multi-tenant isolation
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void multiTenantAggregateIsolation() {
        String tenantA = TEST_TENANT;
        String categoryName = "mt-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);

        var payload1 = new CategoryRegisteredEvent(categoryName, "TenantA-v1");
        AggregateKey keyA = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, tenantA);
        service.publishEvent(PublishDomainEventInput.of(
            tenantA, ConsentEventTypes.CATEGORY_REGISTERED, keyA, payload1
        ));

        CategoryAggregate hydratedA = service.getAggregate(keyA, CategoryAggregate.class);
        assertEquals("TenantA-v1", hydratedA.description());

        // Publish a second version under the same tenant and verify version increments
        var payload2 = new CategoryRegisteredEvent(categoryName, "TenantA-Updated");
        service.publishEvent(PublishDomainEventInput.of(
            tenantA, ConsentEventTypes.CATEGORY_REGISTERED, keyA, payload2
        ));

        CategoryAggregate hydratedA2 = service.getAggregate(keyA, CategoryAggregate.class);
        assertEquals("TenantA-Updated", hydratedA2.description());
    }

    @Test
    @Order(21)
    void multiTenantRedisEventsCarryCorrectTenantId() throws Exception {
        int beforeCount = consumedEvents.size();

        String categoryName = "mt-redis-category-" + UUID.randomUUID();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, TEST_TENANT);
        var payload = new CategoryRegisteredEvent(categoryName, "MT-Redis test");
        service.publishEvent(PublishDomainEventInput.of(
            TEST_TENANT, ConsentEventTypes.CATEGORY_REGISTERED, key, payload
        ));

        awaitConsumedEvents(beforeCount + 1, 6000);

        List<Map<String, String>> tenantEvents = consumedByTenant(TEST_TENANT);
        assertFalse(tenantEvents.isEmpty(), "Should have events for " + TEST_TENANT);

        for (Map<String, String> event : tenantEvents) {
            assertEquals(TEST_TENANT, event.get(TenantEvent.FIELD_TENANT_ID),
                "All events should carry the correct tenantId");
        }
    }
}
