package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PublishEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.koostack.operio.plugin.event.jooq.generated.tables.AggregateSnapshot.AGGREGATE_SNAPSHOT;
import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;
import static org.junit.jupiter.api.Assertions.*;

class JooqPlatformEventServiceTest extends PlatformEventServiceTestBase {

    @Test
    void publishInsertsEventAndHydratesAggregate() {
        String tenantId = "tenant_publish";
        UUID aggregateId = UUID.randomUUID();

        TestAggregate state = new TestAggregate(
            tenantId, aggregateId, "test-aggregate", 1L,
            "Alice", "alice@example.com", 30, true,
            new Address("123 Main St", "Springfield", "IL", "62704"),
            List.of("vip", "beta-tester"),
            List.of(new ConsentPreference("marketing", true, "web-form")),
            Map.of("source", "signup", "plan", "premium")
        );
        PublishEventInput<TestAggregate> input = PublishEventInput.from(
            tenantId, "CONSENT_GRANTED", state
        );

        service.publish(input);

        EventRecord record = dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_ID.eq(aggregateId.toString()))
            .fetchOne();

        assertNotNull(record);
        assertEquals("test-aggregate", record.getAggregateType());
        assertEquals("CONSENT_GRANTED", record.getEventType());
        assertEquals(1L, record.getVersion());
        assertNotNull(record.getChanges());

        AggregateKey key = new AggregateKey(aggregateId, "test-aggregate", tenantId);
        TestAggregate hydrated = service.getAggregate(key, TestAggregate.class);

        assertEquals("Alice", hydrated.name());
        assertEquals("alice@example.com", hydrated.email());
        assertEquals(30, hydrated.age());
        assertTrue(hydrated.active());
        assertEquals("Springfield", hydrated.address().city());
        assertEquals(List.of("vip", "beta-tester"), hydrated.tags());
        assertEquals(1, hydrated.consents().size());
        assertTrue(hydrated.consents().getFirst().granted());
        assertEquals("premium", hydrated.metadata().get("plan"));
    }

    @Test
    void snapshotCreatedAfterTwentyEvents() throws Exception {
        String tenantId = "tenant_snap";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "snap-aggregate", tenantId);

        for (int i = 1; i <= 21; i++) {
            TestAggregate state = new TestAggregate(
                tenantId, aggregateId, "snap-aggregate", (long) i,
                "user-v" + i, "u" + i + "@test.com", 20 + i, i % 2 == 0,
                new Address(i + " Elm St", "City-" + i, "CA", "9000" + i),
                List.of("tag-" + i, "round-" + (i % 3)),
                List.of(
                    new ConsentPreference("analytics", i > 10, "api"),
                    new ConsentPreference("marketing", i % 3 == 0, "admin")
                ),
                Map.of("iteration", String.valueOf(i), "env", "test")
            );
            service.publish(PublishEventInput.from(tenantId, "CONSENT_GRANTED", state));
        }

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
        assertNotNull(snapshot.getSnapshot(), "Snapshot data should not be null");
        assertTrue(snapshot.getSnapshot().contains("user-v"), "Snapshot should contain aggregate name");
        assertTrue(snapshot.getSnapshot().contains("Elm St"), "Snapshot should contain nested address");
        assertTrue(snapshot.getSnapshot().contains("analytics"), "Snapshot should contain consent preferences");
    }

    @Test
    void rehydrateFromSnapshotReturnsCorrectState() throws Exception {
        String tenantId = "tenant_rehydrate";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "rehydrate-aggregate", tenantId);

        for (int i = 1; i <= 21; i++) {
            TestAggregate state = new TestAggregate(
                tenantId, aggregateId, "rehydrate-aggregate", (long) i,
                "pre-snap-" + i, "pre" + i + "@test.com", 25 + i, true,
                new Address("100 Oak Ave", "Oldtown", "NY", "10001"),
                List.of("pre"),
                List.of(new ConsentPreference("analytics", true, "system")),
                Map.of("phase", "pre-snapshot")
            );
            service.publish(PublishEventInput.from(tenantId, "CONSENT_GRANTED", state));
        }

        service.getAggregate(key, TestAggregate.class);
        TimeUnit.SECONDS.sleep(2);

        var snapshotCount = dsl.selectCount()
            .from(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId.toString()))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq("rehydrate-aggregate"))
            .fetchOne(0, int.class);
        assertTrue(snapshotCount > 0, "Snapshot must exist before rehydration test");

        // Publish events that change multiple nested fields
        for (int i = 22; i <= 25; i++) {
            TestAggregate state = new TestAggregate(
                tenantId, aggregateId, "rehydrate-aggregate", (long) i,
                "post-snap-" + i, "post" + i + "@new.com", 50 + i, i % 2 == 0,
                new Address("999 Pine Rd", "Newville", "TX", "75001"),
                List.of("post", "migrated"),
                List.of(
                    new ConsentPreference("analytics", false, "user-revoke"),
                    new ConsentPreference("essential", true, "system")
                ),
                Map.of("phase", "post-snapshot", "migrated", "true")
            );
            service.publish(PublishEventInput.from(tenantId, "CONSENT_GRANTED", state));
        }

        TestAggregate hydrated = service.getAggregate(key, TestAggregate.class);

        assertEquals("post-snap-25", hydrated.name());
        assertEquals("post25@new.com", hydrated.email());
        assertEquals(75, hydrated.age());
        assertFalse(hydrated.active());
        assertEquals("Newville", hydrated.address().city());
        assertEquals("TX", hydrated.address().state());
        assertEquals(List.of("post", "migrated"), hydrated.tags());
        assertEquals(2, hydrated.consents().size());
        assertFalse(hydrated.consents().getFirst().granted());
        assertEquals("true", hydrated.metadata().get("migrated"));
        assertEquals(25L, hydrated.version());
    }

    @Test
    void rehydrateWithoutSnapshotReturnsCorrectState() {
        String tenantId = "tenant_nosnap";
        UUID aggregateId = UUID.randomUUID();
        AggregateKey key = new AggregateKey(aggregateId, "no-snap-aggregate", tenantId);

        for (int i = 1; i <= 5; i++) {
            TestAggregate state = new TestAggregate(
                tenantId, aggregateId, "no-snap-aggregate", (long) i,
                "evolving-" + i, "e" + i + "@test.com", 18 + i, i > 3,
                new Address(i + " Birch Ln", "Smallville", "KS", "6600" + i),
                List.of("iter-" + i),
                List.of(new ConsentPreference("essential", true, "default")),
                Map.of("step", String.valueOf(i))
            );
            service.publish(PublishEventInput.from(tenantId, "CONSENT_GRANTED", state));
        }

        var snapshotCount = dsl.selectCount()
            .from(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId.toString()))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq("no-snap-aggregate"))
            .fetchOne(0, int.class);
        assertEquals(0, snapshotCount);

        TestAggregate hydrated = service.getAggregate(key, TestAggregate.class);

        assertEquals("evolving-5", hydrated.name());
        assertEquals("e5@test.com", hydrated.email());
        assertEquals(23, hydrated.age());
        assertTrue(hydrated.active());
        assertEquals("5 Birch Ln", hydrated.address().street());
        assertEquals("Smallville", hydrated.address().city());
        assertEquals(List.of("iter-5"), hydrated.tags());
        assertEquals("5", hydrated.metadata().get("step"));
        assertEquals(5L, hydrated.version());
    }

    record Address(String street, String city, String state, String zip) {}

    record ConsentPreference(String category, boolean granted, String grantedBy) {}

    record TestAggregate(
        String tenantId,
        UUID aggregateId,
        String aggregateType,
        Long version,
        String name,
        String email,
        int age,
        boolean active,
        Address address,
        List<String> tags,
        List<ConsentPreference> consents,
        Map<String, String> metadata
    ) implements AggregateRoot {}
}
