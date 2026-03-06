package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.events.TenantCreatedEvent;
import com.koostack.operio.core.platform.core.events.TenantPlanChangedEvent;
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
            CoreEventTypes.TENANT_CREATED, "Tenant Created", TenantCreatedEvent.class
        ));

        TenantCreatedEvent payload = new TenantCreatedEvent(tenantId, "Acme Corp", "enterprise");
        service.publishEvent(PublishDomainEventInput.of(
            tenantId, CoreEventTypes.TENANT_CREATED, key, payload
        ));

        setTestSearchPath(tenantId);
        EventRecord record = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .fetchOne();

        assertNotNull(record);
        assertEquals("tenant-lifecycle", record.getAggregateType());
        assertEquals(CoreEventTypes.TENANT_CREATED, record.getEventType());
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
            CoreEventTypes.TENANT_CREATED, "Tenant Created", TenantCreatedEvent.class
        ));
        service.registerEventType(new RegisterEventTypeInput<>(
            CoreEventTypes.TENANT_PLAN_CHANGED, "Tenant Plan Changed", TenantPlanChangedEvent.class
        ));

        service.publishEvent(PublishDomainEventInput.of(
            tenantId, CoreEventTypes.TENANT_CREATED, key,
            new TenantCreatedEvent(tenantId, "Beta Inc", "starter")
        ));
        service.publishEvent(PublishDomainEventInput.of(
            tenantId, CoreEventTypes.TENANT_PLAN_CHANGED, key,
            new TenantPlanChangedEvent(tenantId, "starter", "growth")
        ));

        Long version = service.getAggregateVersion(key);
        assertEquals(2L, version);

        setTestSearchPath(tenantId);
        var events = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .orderBy(EVENT.VERSION.asc())
            .fetch();
        assertEquals(2, events.size());
        assertEquals(CoreEventTypes.TENANT_CREATED, events.get(0).getEventType());
        assertEquals(CoreEventTypes.TENANT_PLAN_CHANGED, events.get(1).getEventType());
    }

    @Test
    void snapshotCreatedAfterTwentyEvents() throws Exception {
        String tenantId = "tenant_snap";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "snap-aggregate", tenantId);

        service.registerEventType(new RegisterEventTypeInput<>(
            CoreEventTypes.TENANT_PLAN_CHANGED, "Tenant Plan Changed", TenantPlanChangedEvent.class
        ));

        for (int i = 1; i <= 21; i++) {
            service.publishEvent(PublishDomainEventInput.of(
                tenantId, CoreEventTypes.TENANT_PLAN_CHANGED, key,
                new TenantPlanChangedEvent(tenantId, "plan-" + (i - 1), "plan-" + i)
            ));
        }

        // Snapshot is triggered automatically at version 20 during publishEvent
        TimeUnit.SECONDS.sleep(2);

        setTestSearchPath(tenantId);
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
}
