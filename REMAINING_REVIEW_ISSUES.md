# Remaining Review Issues

Issues identified during review of the tenant event module refactoring and sharded Redis stream publishing work. Provides enough context for a fresh session to pick up and fix each item.

---

## Background: What was done

The `operio-tenant-event-jooq` module was created as a copy of `operio-platform-event-jooq`, adapted for tenant-scoped events:

- **`TenantEvent`** model (operio-core) — like `PlatformEvent` but with a `tenantId` field
- **`TenantEventService`** interface (operio-core) — mirrors `PlatformEventService`
- **`PlatformEvent`** — had `tenantId` removed (platform events are not tenant-scoped)
- **`JooqConsentAdminCommandService`** — migrated from `PlatformEventService` to `TenantEventService`
- **Sharded Redis publishing** — tenant events publish to 16 sharded streams (`operio:stream:domain:0` … `operio:stream:domain:15`) based on `tenantId.hashCode()`. The `RedisEventPublisher` owns the sharding logic; the `OutboxPoller` just passes event fields to `publishRaw()`.

### Key files

| File | Role |
|------|------|
| `tenant/operio-tenant-event-jooq/src/main/java/.../RedisEventPublisher.java` | Sharded stream publishing + `streamKeyForTenant()` |
| `tenant/operio-tenant-event-jooq/src/main/java/.../OutboxPoller.java` | Transactional outbox — polls unpublished events, builds field maps, delegates to RedisEventPublisher |
| `tenant/operio-tenant-event-jooq/src/main/java/.../JooqTenantEventService.java` | Main service impl — wires EventRepository, OutboxPoller, SnapshotWorker |
| `tenant/operio-tenant-event-jooq/src/main/java/.../EventRepository.java` | jOOQ persistence — insert/fetch events, snapshots, event types |
| `tenant/operio-consent-admin-command-jooq/src/main/java/.../ConsentAdminCommandConfig.java` | Config record now holds `TenantEventService<?>` (was `PlatformEventService<?>`) |
| `tenant/operio-consent-admin-command-jooq/src/main/java/.../JooqConsentAdminCommandService.java` | Event-sourced consent commands, uses `TenantEventService` |
| `tenant/operio-consent-admin-command-jooq/src/test/java/.../ConsentAdminCommandTestBase.java` | Integration test base (Testcontainers: Postgres + Redis) |
| `tenant/operio-consent-admin-command-jooq/src/test/java/.../JooqConsentAdminCommandServiceTest.java` | Integration tests for consent commands |

---

## Issue 1: NPE in `RedisEventPublisher.streamKeyForTenant(null)`

**File:** `tenant/operio-tenant-event-jooq/src/main/java/com/koostack/operio/tenant/event/jooq/RedisEventPublisher.java`
**Lines:** 37–38, 51–54

**Severity:** Bug (crash path)

**Problem:**
`publishRaw()` extracts `tenantId` from the fields map via `fields.get(TenantEvent.FIELD_TENANT_ID)`. If the field is absent (returns `null`), it passes `null` to `streamKeyForTenant()`, which calls `tenantId.hashCode()` — a `NullPointerException`.

This can happen because `OutboxPoller.publishBatch()` (line 138) conditionally puts `FIELD_TENANT_ID` into the map:
```java
if (tenantId != null) {
    fields.put(TenantEvent.FIELD_TENANT_ID, tenantId);
}
```

If `TenantContext.get()` returns `null`, the field is omitted and the publisher crashes.

**Fix:** Add a null guard at the top of `streamKeyForTenant`:
```java
public static String streamKeyForTenant(String tenantId) {
    if (tenantId == null) {
        throw new IllegalArgumentException("tenantId must not be null for shard routing");
    }
    int hash = tenantId.hashCode();
    int partitionIndex = (hash & 0x7FFFFFFF) % SHARD_COUNT;
    return STREAM_KEY_PREFIX + partitionIndex;
}
```

Also consider whether `OutboxPoller.publishBatch()` should fail fast if `TenantContext.get()` is null rather than silently omitting the field. A tenant event without a `tenantId` is invalid.

---

## Issue 2: Missing `FIELD_TENANT_ID` assertion in integration test

**File:** `tenant/operio-consent-admin-command-jooq/src/test/java/com/koostack/operio/plugin/consent/jooq/JooqConsentAdminCommandServiceTest.java`
**Lines:** 578–585 (test `allEventsContainRequiredStreamFields`, `@Order(100)`)

**Severity:** Test gap

**Problem:**
The test asserts presence of `eventId`, `aggregateId`, `aggregateType`, `eventType`, `version`, and `changes` in consumed Redis events — but **never asserts `tenantId`**. Since these are tenant events, every message in the stream must carry `FIELD_TENANT_ID`.

**Fix:** Add this assertion inside the `for` loop:
```java
assertNotNull(event.get(TenantEvent.FIELD_TENANT_ID), "Missing tenantId");
```

---

## Issue 3: `SHARD_COUNT` not publicly accessible

**File:** `tenant/operio-tenant-event-jooq/src/main/java/com/koostack/operio/tenant/event/jooq/RedisEventPublisher.java`
**Line:** 23

**Severity:** Design concern

**Problem:**
`SHARD_COUNT` is package-private (`static final int SHARD_COUNT = 16`). Any consumer service that needs to subscribe to all 16 shards (e.g., a consent-sync projection reading from Redis) cannot discover the shard count programmatically.

**Fix options:**
- Make `SHARD_COUNT` `public`
- Or expose a utility method like `public static List<String> allStreamKeys()` that returns all 16 keys

---

## Issue 4: No unit tests for `streamKeyForTenant` sharding logic

**Severity:** Test gap

**Problem:**
The sharding function is critical for correct event routing but has zero test coverage. Issues like negative hash codes, off-by-one in modulo, or inconsistent hashing across JVM versions would go undetected.

**Suggested tests (can be a simple JUnit class in `operio-tenant-event-jooq`):**
```java
@Test void sameInputAlwaysReturnsSameKey() { ... }
@Test void resultAlwaysInValidRange() {
    // test many tenant IDs, assert partition index is in [0, SHARD_COUNT)
}
@Test void nullInputThrows() { ... }  // after Issue 1 fix
@Test void keyHasExpectedPrefix() {
    assertTrue(RedisEventPublisher.streamKeyForTenant("x").startsWith("operio:stream:domain:"));
}
```

---

## Issue 5: No direct integration tests for `operio-tenant-event-jooq`

**Severity:** Test gap (medium)

**Problem:**
The `tenant/operio-tenant-event-jooq` module has **no test directory at all**. All testing happens indirectly through the consent-admin-command integration tests. The sibling `plugins/operio-platform-event-jooq` module has its own Testcontainers-based test suite that directly tests the event service, outbox poller, snapshot worker, and Redis publishing.

**Suggested:** Mirror the platform-event-jooq test structure:
- `JooqTenantEventServiceTest` — publish, publishTransform, getAggregate, getAggregateVersion
- Outbox poller publishes to correct sharded stream key
- Snapshot worker creates snapshots after threshold depth
- Multi-tenant isolation (two tenants, events stay separated)

---

## Issue 6: No multi-tenant test verifying shard isolation

**Severity:** Test gap

**Problem:**
All integration tests use a single tenant (`test_tenant`). There is no test verifying:
- Two different tenants can independently publish events
- Events from tenant A don't appear in tenant B's queries
- Different tenants may map to different (or the same) shard, and both work correctly

**Suggested:** Add a test in the consent-admin-command suite (or the new tenant-event-jooq tests) that:
1. Configures `tenantDiscovery` to return `["tenant_a", "tenant_b"]`
2. Publishes events under each tenant context
3. Verifies aggregate state per-tenant is independent
4. Verifies Redis stream(s) contain events from both tenants with correct `tenantId` fields

---

## Previously identified issues (from earlier review session)

### Issue 7: H2 V7 migration reassigns `event_type_id` values

**File:** `plugins/operio-platform-event-jooq/src/main/resources/db/migration/platform-event/V7__drop_event_type_fqn_unique.sql`

**Severity:** Medium (currently safe, fragile for future changes)

**Problem:**
The H2 migration drops the unique constraint on `event_type.fqn` by recreating the table with `GENERATED ALWAYS AS IDENTITY`, which assigns new auto-generated IDs to copied rows. Currently safe because nothing references `event_type_id` by value, but would break if a FK is ever added.

**Fix:** Use `GENERATED BY DEFAULT AS IDENTITY` and copy the original IDs. Or accept as H2-only (build-time codegen), since PostgreSQL uses a clean `ALTER TABLE DROP CONSTRAINT`.

### Issue 8: `JooqPlatformEventService` has no shutdown method

**File:** `plugins/operio-platform-event-jooq/src/main/java/com/koostack/operio/plugin/event/jooq/JooqPlatformEventService.java`

**Severity:** Medium

**Problem:**
Unlike `JooqTenantEventService` (which has `stop()`), the platform event service doesn't expose a way to shut down its `OutboxPoller` and `SnapshotWorker`. This can cause thread pool leaks in tests and application shutdown.

**Fix:** Add a `stop()` method mirroring `JooqTenantEventService.stop()`.

---

## Pre-existing unrelated issue

**`plugins/operio-consent-sync`** has a compilation error referencing a missing `JooqConsentAdminService` class. This failure exists on the main branch and is not related to any of the work described here.
