package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.control.events.PlatformEventTypes;
import com.koostack.operio.core.control.events.TenantRegisteredEvent;
import com.koostack.operio.core.control.events.BillingPlanChangedEvent;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.koostack.operio.plugin.event.jooq.generated.tables.AggregateSnapshot.AGGREGATE_SNAPSHOT;
import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;
import static org.junit.jupiter.api.Assertions.*;

class JooqPlatformEventServiceTest extends PlatformEventServiceTestBase {

    @Test
    void publishEventInsertsEventAndHydrates() {
        String tenantId = "tenant_publish";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "tenant-lifecycle", tenantId);

        service.registerEventType(new RegisterEventTypeInput<>(
            PlatformEventTypes.TENANT_REGISTERED, "Tenant Registered", TenantRegisteredEvent.class
        ));

        TenantRegisteredEvent payload = new TenantRegisteredEvent(tenantId, "Acme Corp", "enterprise");
        service.publishEvent(PublishDomainEventInput.of(
            tenantId, PlatformEventTypes.TENANT_REGISTERED, key, payload
        ));

        EventRecord record = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .fetchOne();

        assertNotNull(record);
        assertEquals("tenant-lifecycle", record.getAggregateType());
        assertEquals(PlatformEventTypes.TENANT_REGISTERED, record.getEventType());
        assertEquals(1L, record.getVersion());
        assertNotNull(record.getPayload());
        assertTrue(record.getPayload().contains("Acme Corp"));

        Long version = service.getAggregateVersion(key);
        assertEquals(1L, version);
    }

    @Test
    void multipleEventsIncrementVersion() {
        String tenantId = "tenant_multi";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "tenant-lifecycle", tenantId);

        service.registerEventType(new RegisterEventTypeInput<>(
            PlatformEventTypes.TENANT_REGISTERED, "Tenant Registered", TenantRegisteredEvent.class
        ));
        service.registerEventType(new RegisterEventTypeInput<>(
            PlatformEventTypes.BILLING_PLAN_CHANGED, "Billing Plan Changed", BillingPlanChangedEvent.class
        ));

        service.publishEvent(PublishDomainEventInput.of(
            tenantId, PlatformEventTypes.TENANT_REGISTERED, key,
            new TenantRegisteredEvent(tenantId, "Beta Inc", "starter")
        ));
        service.publishEvent(PublishDomainEventInput.of(
            tenantId, PlatformEventTypes.BILLING_PLAN_CHANGED, key,
            new BillingPlanChangedEvent(tenantId, "starter", "growth")
        ));

        Long version = service.getAggregateVersion(key);
        assertEquals(2L, version);

        var events = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .orderBy(EVENT.VERSION.asc())
            .fetch();
        assertEquals(2, events.size());
        assertEquals(PlatformEventTypes.TENANT_REGISTERED, events.get(0).getEventType());
        assertEquals(PlatformEventTypes.BILLING_PLAN_CHANGED, events.get(1).getEventType());
    }

    @Test
    void snapshotCreatedAfterTwentyEvents() throws Exception {
        String tenantId = "tenant_snap";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "snap-aggregate", tenantId);

        service.registerEventType(new RegisterEventTypeInput<>(
            PlatformEventTypes.BILLING_PLAN_CHANGED, "Billing Plan Changed", BillingPlanChangedEvent.class
        ));

        for (int i = 1; i <= 21; i++) {
            service.publishEvent(PublishDomainEventInput.of(
                tenantId, PlatformEventTypes.BILLING_PLAN_CHANGED, key,
                new BillingPlanChangedEvent(tenantId, "plan-" + (i - 1), "plan-" + i)
            ));
        }

        // Trigger hydration to enqueue snapshot
        service.getAggregate(key, com.fasterxml.jackson.databind.JsonNode.class);
        TimeUnit.SECONDS.sleep(2);

        var snapshot = dsl.selectFrom(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId.toString()))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq("snap-aggregate"))
            .orderBy(AGGREGATE_SNAPSHOT.VERSION.desc())
            .limit(1)
            .fetchOne();

        assertNotNull(snapshot, "Snapshot should have been created after 21 events");
        assertTrue(snapshot.getVersion() >= 20, "Snapshot version should be >= 20");
        assertNotNull(snapshot.getSnapshot(), "Snapshot data should not be null");
    }

    @Test
    void rejectsUnknownEventType() {
        String tenantId = "tenant_unknown";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "test-aggregate", tenantId);

        assertThrows(Exception.class, () ->
            service.publishEvent(PublishDomainEventInput.of(
                tenantId, "nonexistent.event.type", key,
                new TenantRegisteredEvent(tenantId, "Test", "free")
            ))
        );
    }
}
