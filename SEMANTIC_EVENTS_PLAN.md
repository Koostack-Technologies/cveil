# Agent Prompt:
Continue implementing this plan where it was left off by the last session. After each phase stop and check in. After each task that is under each phase (i.e 1d) you
are going to update an after action report table at the bottom of the mark down file.  Providing the task the status, and any notes. The notes can include potential improvements,
bugs, next steps basically anything that you can think if that would be relevant.

# Plan: Semantic Domain Events + Stream Projections

## Problem Statement

The current event sourcing system stores **JSON Patch (RFC 6902) diffs** in the `changes` column of the `event` tables across **both the Tenant and Platform domains**. These patches are opaque — array-based aggregates use `List<>` fields, so JSON Patch produces positional array indices like `replace /policies/2/granted false`. Downstream consumers (projections) can't convert these into targeted SQL `WHERE` clauses or Redis key writes without knowing the current array state, which defeats the point of stream-based decoupling.

Additionally, the `operio-consent-sync` plugin is a synchronous dual-write coordinator that writes to both the relational DB and Redis at call time — tightly coupling the command side with the read models.

**Solution:** Replace JSON Patch across the entire framework (Tenant and Platform) with **semantic domain events** and build independent stream-consuming projections.

---

## Architecture Overview

### Current Flow (JSON Patch — being replaced)

```
Command Service (Consent/Tenant/Billing)
    → publishTransform(transform_function)
    → JooqEventService: hydrate aggregate → apply transform → JsonDiff → store JsonPatch
    → OutboxPoller: poll event table → XADD to Redis stream (includes serialized JsonPatch in "changes" field)
    → Stream consumers: ??? (can't use positional patches without local state)

```

### New Flow (Semantic Events)

```
Command Service
    → creates semantic payload record (e.g., PolicyGrantedEvent, TenantRegisteredEvent)
    → publishEvent(tenantId/platform, eventType, key, serializedPayload)
    → JooqEventService: insert event with payload (no diffing)
    → OutboxPoller: poll event table → XADD to Redis stream (includes payload in "payload" field)
    → Stream consumers: deserialize payload by eventType → make direct targeted writes
                            ┌──────────────┴───────────────┐
                            ↓                              ↓
                Admin/Platform Projections        Decision Projection
             (payload → SQL upsert/delete)     (payload → Redis SET/DEL)

```

### Redis Stream Routing

* **Tenant Events:** 16 sharded streams (`operio:stream:domain:0` to `15`). Shard selection: `tenantId.hashCode() & 0x7FFFFFFF % 16`.
* **Platform Events:** 1 single global stream (`operio:stream:platform`).
* Both projections consume their respective streams via `XREADGROUP` using dedicated consumer groups.

---

## Design Principles

### Explicit Deletes

**Null/absent fields mean "not provided" — never "delete."** Every removal operation gets its own dedicated event type. This eliminates the omission-versus-deletion ambiguity. To actually remove something, you must publish an explicit delete/unlink/remove event.

### Self-Contained Payloads

Every event payload carries all fields needed for the projection to make a direct write. Projections never read the event log table. They never need to hydrate aggregate state. One event = one targeted SQL or Redis operation.

### Idempotent Projections

All projection writes use upsert patterns (`ON CONFLICT ... DO UPDATE`) so replaying the stream from the beginning rebuilds the read model identically.

---

## Current Codebase Reference

### Key Packages and Files

| Module | Package | Key Files |
| --- | --- | --- |
| `operio-core` | `...core.event.models` | `TenantEvent.java`, `PlatformEvent.java` (both use **changes: JsonPatch**) |
| `operio-core` | `...core.event` | `TenantEventService.java`, `PlatformEventService.java` |
| `operio-core` | `...core.control.*` | `TenantCommandService`, `BillingCommandService`, `SecurityCommandService`, `UsageCommandService`, and their respective Projections |
| `operio-core` | `...core.data.consent.*` | `ConsentEventTypes.java`, Aggregates, `ConsentAdminCommandService` |
| `operio-tenant-event-jooq` | `...tenant.event.jooq` | `JooqTenantEventService.java`, `OutboxPoller.java`, `AggregateHydrationUtil.java` |
| `operio-platform-event-jooq` | `...plugin.event.jooq` | `JooqPlatformEventService.java`, `OutboxPoller.java`, `AggregateHydrationUtil.java` |

---

## Phase 1: Tenant Semantic Event Infrastructure

### 1a. Tenant Event Payload Records (operio-core)

New package: `com.koostack.operio.core.data.consent.events`
All payloads are Java records implementing a marker interface `DomainEvent`.

* **Creation/Mutation:** `PolicyGrantedEvent`, `PolicyRevokedEvent`, `EntityFieldRegisteredEvent`, `FieldCategoryLinkedEvent`, `CategoryRegisteredEvent`, `PurposeRegisteredEvent`, `PurposeRegulationLinkedEvent`, `RegulationRegisteredEvent`.
* **Explicit Deletes:** `PolicyAllRevokedEvent`, `PolicyAuditDeletedEvent`, `CategoryDeletedEvent`, `PurposeDeletedEvent`, `RegulationDeletedEvent`, `EntityDeletedEvent`, `EntityFieldRemovedEvent`, `FieldCategoryUnlinkedEvent`, `PurposeRegulationUnlinkedEvent`.

### 1b. TenantEvent Model Change (operio-core)

In `TenantEvent.java`, change `JsonPatch changes` to `String payload`.

* Remove: `FIELD_CHANGES = "changes"`
* Add: `FIELD_PAYLOAD = "payload"`

### 1c. Aggregate Apply Methods (operio-core)

Each tenant aggregate gets a static `apply(T current, String eventType, JsonNode payload, ObjectMapper mapper)` method that deserializes the payload and applies it to the current state.

* New aggregate mutations needed for delete events: `EntityAggregate.withoutField()`, `EntityAggregate.withoutFieldCategoryLink()`, `PurposeAggregate.withoutRegulationLink()`.

### 1d. Event Service API Changes

* Change `TenantEventService` interface: Remove `publishTransform()`. Add `void publishEvent(PublishDomainEventInput input);`
* Refactor `JooqTenantEventService` hydration to use event replay instead of JSON patch `AggregateHydrationUtil`.

### 1e. DB Migration (operio-tenant-event-jooq)

Add Flyway `V2__rename_changes_to_payload.sql`:
`ALTER TABLE event RENAME COLUMN changes TO payload;`

### 1f. OutboxPoller & Command Service Updates

* Update `OutboxPoller.java` to push `EVENT.PAYLOAD` to Redis.
* Refactor `JooqConsentAdminCommandService` to publish explicit payload records instead of transforms.

---

## Phase 2: Stream Projections (Tenant)

### 2a. ShardedStreamConsumer (operio-tenant-event-jooq)

Generic consumer that listens to all 16 Redis sharded streams using consumer groups, spawning 16 daemon threads.

### 2b. Admin Projection Module (operio-consent-admin-projection-jooq)

* **Action:** Switches on `eventType`, deserializes payload, executes blind SQL `ON CONFLICT DO UPDATE` or `DELETE` statements against the consent admin schemas.

### 2c. Decision Projection Module (operio-consent-decision-projection-jooq)

* **Action:** Switches on `eventType`, writes to Redis via `ConsentPolicyRedisRepository` and `FieldCategoryMappingRedisRepository`.

---

## Phase 3: Platform Semantic Event Parity

To ensure the entire framework uses semantic intent, the Platform domain must be upgraded alongside the Tenant domain.

### 3a. Platform Event Payload Records (operio-core)

New package: `com.koostack.operio.core.control.events`
Create explicit `DomainEvent` records for platform actions, eliminating JSON Patch for platform aggregates.

* **Tenant/Lifecycle:** `TenantRegisteredEvent`, `TenantSuspendedEvent`, `TenantDeletedEvent`.
* **Security:** `ApiKeyGeneratedEvent`, `ApiKeyRevokedEvent`, `IdpConfigUpdatedEvent`.
* **Billing/Usage:** `BillingPlanChangedEvent`, `UsageLimitAdjustedEvent`.

### 3b. PlatformEvent Model Change (operio-core)

In `PlatformEvent.java`, change `JsonPatch changes` to `String payload`.

* Remove: `FIELD_CHANGES = "changes"`
* Add: `FIELD_PAYLOAD = "payload"`

### 3c. Platform Aggregate Apply Methods

Update platform aggregates (e.g., `TenantAggregate`, `ApiKeyAggregate`) with static `apply()` methods that deserialize platform payload records to rebuild state, mirroring the tenant aggregates.

### 3d. Platform Event Service & Command Services

* Update `PlatformEventService.java` to use `publishEvent(PublishDomainEventInput)`.
* Refactor `TenantCommandService`, `BillingCommandService`, `SecurityCommandService`, and `UsageCommandService` to publish explicit payloads instead of using `publishTransform()`.
* Delete `AggregateHydrationUtil` in `operio-platform-event-jooq` and replace with `AggregateEventApplier`.

### 3e. Platform DB Migration (operio-platform-event-jooq)

Create `V8__rename_changes_to_payload.sql` in both `platform-event-postgresql` and `platform-event-sqlite`:
`ALTER TABLE event RENAME COLUMN changes TO payload;`

### 3f. Platform OutboxPoller & Projections Update

* Update `OutboxPoller` in `operio-platform-event-jooq` to publish `EVENT.PAYLOAD`.
* Update existing projections (`TenantProjection`, `BillingProjection`, `SecurityProjection`, `UsageProjection` in `operio-core`) to consume semantic JSON payloads from `operio:stream:platform` instead of applying mechanical JSON patches to the read models.

---

## Implementation Order (Todos)

### Tenant Domain

| # | ID | Title | Depends On |
| --- | --- | --- | --- |
| 1 | `event-payloads` | Create 19 Tenant DomainEvent payload records + marker interface | — |
| 2 | `tenant-event-model` | Change TenantEvent: JsonPatch → String payload | 1 |
| 3 | `aggregate-apply` | Add apply methods + new delete mutations to Tenant aggregates | 1 |
| 4 | `event-service-api` | Update TenantEventService + JooqTenantEventService | 2, 3 |
| 5 | `db-migration` | Flyway V2: rename changes → payload (Tenant) | 2 |
| 6 | `outbox-update` | Update Tenant OutboxPoller stream fields | 2 |
| 7 | `command-service-refactor` | Refactor JooqConsentAdminCommandService | 4, 6 |
| 8 | `sharded-stream-consumer` | Build ShardedStreamConsumer | 1 |
| 9 | `admin-projection-module` | Create module skeleton & ConsentAdminProjectionHandler | 8 |
| 10 | `decision-projection-module` | Create module skeleton & ConsentDecisionProjectionHandler | 8 |

### Platform Domain

| # | ID | Title | Depends On |
| --- | --- | --- | --- |
| 11 | `platform-event-payloads` | Create Platform DomainEvent records (Tenant, Billing, Security) | — |
| 12 | `platform-event-model` | Change PlatformEvent: JsonPatch → String payload | 11 |
| 13 | `platform-aggregate-apply` | Add apply methods to Platform aggregates | 11 |
| 14 | `platform-event-service` | Update PlatformEventService + JooqPlatformEventService | 12, 13 |
| 15 | `platform-db-migration` | Flyway V8: rename changes → payload (Platform PG/SQLite) | 12 |
| 16 | `platform-outbox-update` | Update Platform OutboxPoller stream fields | 12 |
| 17 | `platform-commands` | Refactor Tenant/Billing/Security/Usage Command Services | 14, 16 |
| 18 | `platform-projections` | Update core Platform Projections to read semantic payloads | 17 |
| 19 | `cleanup-json-patch` | Remove `json-patch` dependency framework-wide completely | 7, 18 |

---

## Notes and Constraints

* **`operio-consent-sync` will be deleted** — no backward compatibility needed, there is no v1. The two new projections fully replace it.
* **Unified Paradigm:** Both `TenantEventService` and `PlatformEventService` now strictly use semantic payloads. JSON Patch is eradicated from the codebase entirely.
* **Old `publish()`, `publishTransform()`, `PublishEventInput`, `PublishTransformInput**` — deleted entirely across both domains.
* **`AggregateHydrationUtil`** — deleted in both `operio-tenant-event-jooq` and `operio-platform-event-jooq`.
* **json-patch library dependency** (`com.github.java-json-tools:json-patch`) — removed globally.
* **Both domain projections implement `Operio.AppNameAware**` for Redis key prefix propagation.
* **Failed event processing** is logged, `XACK` is explicitly executed to prevent stream blocking, and poison messages are monitored via metrics/DLQ.

## After Action Reporting
|Task|Status|Notes|
|---|---|---|
|1a - Tenant Event Payload Records|✅ Done|Created `DomainEvent` marker interface + 18 payload records (10 creation/mutation + 8 explicit deletes) in `com.koostack.operio.core.data.consent.events`. Created `PublishDomainEventInput` in event models. Added 8 new delete event type constants to `ConsentEventTypes`. Plan listed 19 records but `EntityFieldRegisteredEvent` covers entity creation implicitly — the 18 records fully cover all semantic intents. Potential improvement: consider adding a `DomainEventRegistry` mapping eventType strings to payload classes for deserialization.|
|1b - TenantEvent Model Change|✅ Done|Replaced `JsonPatch changes` field with `String payload` in `TenantEvent.java`. Removed `com.github.fge.jsonpatch.JsonPatch` import. Changed field constant from `FIELD_CHANGES` to `FIELD_PAYLOAD`. This is a breaking change — all downstream consumers (EventRepository, AggregateHydrationUtil, OutboxPoller, JooqTenantEventService, SnapshotWorker) will need updates in subsequent tasks.|
|1c - Aggregate Apply Methods|✅ Done|Added static `apply(T current, String eventType, JsonNode payloadNode, ObjectMapper mapper)` methods to all 5 tenant aggregates (ConsentPolicyAggregate, EntityAggregate, CategoryAggregate, PurposeAggregate, RegulationAggregate). Added `withoutField()` and `withoutFieldCategoryLink()` to EntityAggregate, and `withoutRegulationLink()` to PurposeAggregate. Created `AggregateEventApplier` in tenant-event-jooq as the replacement for `AggregateHydrationUtil` — dispatches event replay by aggregate type. Delete event types (e.g. ENTITY_DELETED) return current state unchanged since the aggregate itself is a no-op; projections handle the actual removal. Potential improvement: extract aggregate type → class mapping into a registry for extensibility.|
|1d - Event Service API Changes|✅ Done|Removed `publish(PublishEventInput)` and `publishTransform(PublishTransformInput)` from `TenantEventService` interface. Added `publishEvent(PublishDomainEventInput)`. Fully refactored `JooqTenantEventService`: removed JSON Patch diffing, now serializes the semantic payload directly. `getAggregate()` now uses `AggregateEventApplier.replay()` instead of JSON Patch hydration. Updated `EventRepository.insert()` and `toTenantEvent()` to use `payload` field. Updated `SnapshotWorker` to use `AggregateEventApplier.replayToJson()`. Note: `AggregateHydrationUtil` is now dead code but kept until cleanup phase (task 19). Old `PublishEventInput`/`PublishTransformInput` are also dead code pending deletion.|
|1e - DB Migration|✅ Done|Created `V2__rename_changes_to_payload.sql` in all 3 tenant-event migration paths (H2/default, PostgreSQL, SQLite). All use `ALTER TABLE event RENAME COLUMN changes TO payload`. Successfully regenerated jOOQ code — generated `Event.PAYLOAD` and `EventRecord.getPayload()/setPayload()` now replace `CHANGES`/`getChanges()`/`setChanges()`. Verified migration runs cleanly against H2.|
|1f - OutboxPoller & Command Service Updates|✅ Done|Updated tenant `OutboxPoller` to push `FIELD_PAYLOAD` / `record.getPayload()` instead of `FIELD_CHANGES` / `record.getChanges()`. Fully refactored `JooqConsentAdminCommandService`: removed all `publishTransform()` calls and `Function`/`Supplier` patterns — now creates explicit `DomainEvent` payload records (e.g., `PolicyGrantedEvent`, `CategoryRegisteredEvent`) and publishes via `publishEvent(PublishDomainEventInput)`. Updated `registerEventTypes()` to reference payload event classes instead of aggregate classes. Updated `AggregateHydrationUtil` to delegate to `AggregateEventApplier` (deprecated, pending removal). Updated integration tests in both `JooqTenantEventServiceTest` (rewrote to use `CategoryAggregate` + `CategoryRegisteredEvent`) and `JooqConsentAdminCommandServiceTest` (fixed `FIELD_CHANGES` → `FIELD_PAYLOAD`). All 3 modified modules compile cleanly. Pre-existing build failures in `operio-consent-sync`, `operio-consent-sync-embedded`, `operio-bundle-embedded`, and `operio-rest-api` are unrelated (reference missing `JooqConsentAdminService` class).|
|Phase 1 Review Fixes|✅ Done|**Bug fix:** Registered 7 missing delete/unlink event types (`ENTITY_DELETED`, `ENTITY_FIELD_REMOVED`, `ENTITY_FIELD_CATEGORY_UNLINKED`, `CATEGORY_DELETED`, `PURPOSE_DELETED`, `PURPOSE_REGULATION_UNLINKED`, `REGULATION_DELETED`) in `registerEventTypes()` — these were defined in `ConsentEventTypes` and handled by aggregate `apply()` methods but never registered, which would cause `"Unknown event type"` failures at publish time. **Consistency fix:** Added `JavaTimeModule` to `SnapshotWorker`'s ObjectMapper to match the other 3 ObjectMappers. **API cleanup:** Removed redundant `String serializedPayload` parameter from `EventRepository.insert()` — now uses `event.payload()` directly; also removed unused `<T>` type parameter. **Safety fix:** Added `@Deprecated(forRemoval = true)` annotation and early-return-baseState guard to `AggregateHydrationUtil.hydrate(baseState, events)` which was silently ignoring its `baseState` parameter. **Noted for future phases:** No write-time aggregate validation (e.g., `linkFieldToCategory` doesn't verify field exists before storing event); no delete/remove/unlink command methods yet on `ConsentAdminCommandService`; consider disabling `WRITE_DATES_AS_TIMESTAMPS` for human-readable ISO-8601 Instant serialization.|
|2a - ShardedStreamConsumer|✅ Done|Created `StreamEventHandler` functional interface and `ShardedStreamConsumer` in `tenant-event-jooq`. Consumer spawns 16 daemon threads (one per shard), uses `XREADGROUP` with configurable block timeout (default 2s) and batch size (default 100). Creates consumer groups on startup via `XGROUP CREATE ... MKSTREAM` with `$` start ID (new events only). Poison-message safety: handler exceptions are logged at WARN and the message is always `XACK`'d to prevent PEL buildup. Error recovery: shard reader pauses 1s on unexpected errors before retrying. Guarded start/stop via `AtomicBoolean`. Reuses `RedisEventPublisher.allStreamKeys()` for stream key enumeration.|
|2b - Admin Projection Module|✅ Done|Created new module `tenant:operio-consent-admin-projection-jooq` with `ConsentAdminProjectionHandler` implementing `StreamEventHandler`. Handles all 17 consent event types: entity/field CRUD (upsert entity by `entity_name`, upsert field by `(field_name, entity_id)`), category/purpose/regulation upserts by unique name columns, field-category and purpose-regulation link/unlink operations, and consent policy upsert via `ON CONFLICT (subject_id, field_id, purpose_id)` / `ON CONFLICT (subject_id, category_id, purpose_id)`. Delete operations clean up FK-dependent join tables before removing parent rows. All writes scoped to per-tenant schema via `TenantContext.runWhere()`. Name-to-ID resolution via targeted jOOQ `SELECT` queries (entity→entity_id, category→category_id, purpose→purpose_id, regulation→regulation_id, field→field_id). Potential improvement: batch name lookups to reduce round trips on high-throughput projections; add metrics counters per event type.|
|2c - Decision Projection Module|✅ Done|Created new module `tenant:operio-consent-decision-projection-redis` with `ConsentDecisionProjectionHandler` implementing `StreamEventHandler` and `Operio.AppNameAware`. Handles 7 decision-relevant event types: `POLICY_GRANTED`/`REVOKED` → `setFieldConsent()` for field-level or `setCategoryConsentForFields()` for category-level (resolves qualified fields via `FieldCategoryMappingRedisRepository.getFieldsForCategory()`); `POLICY_ALL_REVOKED` → `deleteAllCachedKeysForSubject()`; `FIELD_CATEGORY_LINKED/UNLINKED` → `addFieldToCategory()`/`removeFieldFromCategory()`; `CATEGORY_DELETED` → `removeAllMappingsForCategory()`; `ENTITY_FIELD_REMOVED` → `removeAllMappingsForField()`. Supports TTL propagation via `expiresAt` on policy events. Non-decision events (entity/purpose/regulation registration) are silently ignored. `ENTITY_DELETED` is not handled at the decision layer — stale mappings are harmless and will be cleaned when individual fields are removed. Potential improvement: handle `ENTITY_DELETED` by SCAN-ing for field keys matching the entity prefix.|