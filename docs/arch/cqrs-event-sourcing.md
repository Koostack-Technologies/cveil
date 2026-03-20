# CQRS & Event Sourcing Architecture

## Overview

Cveil implements the **Command Query Responsibility Segregation (CQRS)** pattern combined with **Event Sourcing** across all bounded contexts. Commands produce semantic domain events that are persisted to an event store, then asynchronously projected to read-optimized models via Redis Streams.

## Architecture Diagram

```
┌──────────────┐     ┌──────────────┐     ┌────────────────────────┐
│   Command    │     │    Event     │     │     Redis Stream       │
│   Service    │────▶│    Store     │────▶│  (Outbox Poller)       │
│              │     │ (PostgreSQL) │     │                        │
└──────────────┘     └──────────────┘     └───────────┬────────────┘
                                                      │
                                          ┌───────────▼────────────┐
                                          │   Stream Consumers     │
                                          │                        │
                                          │  ┌──────────────────┐  │
                                          │  │ Admin Projection  │  │
                                          │  │ (jOOQ → SQL)     │  │
                                          │  └──────────────────┘  │
                                          │  ┌──────────────────┐  │
                                          │  │Decision Projection│  │
                                          │  │ (Redis HSET)     │  │
                                          │  └──────────────────┘  │
                                          └────────────────────────┘

┌──────────────┐     ┌──────────────┐
│    Query     │     │  Read Model  │
│   Service    │◀────│  (jOOQ/Redis)│
│              │     │              │
└──────────────┘     └──────────────┘
```

## Semantic Domain Events

The system uses **semantic domain events** (not JSON Patch diffs). Each event carries a self-describing payload that downstream projections can process without needing the current aggregate state.

### Event Types by Domain

**Consent Admin (Tenant)**
- `ConsentGrantedEvent`, `ConsentRevokedEvent`, `ConsentExpiredEvent`, `ConsentAllRevokedEvent`
- `CategoryRegisteredEvent`, `CategoryUpdatedEvent`, `CategoryRemovedEvent`
- `EntityRegisteredEvent`, `EntityUpdatedEvent`, `EntityRemovedEvent`
- `FieldRegisteredEvent`, `FieldUpdatedEvent`, `FieldRemovedEvent`
- `FieldCategoryAssignedEvent`, `FieldCategoryUnassignedEvent`
- `PurposeRegisteredEvent`, `PurposeUpdatedEvent`, `PurposeRemovedEvent`
- `PurposePolicyAddedEvent`, `PurposePolicyRemovedEvent`
- `PurposeRegulationLinkedEvent`, `PurposeRegulationUnlinkedEvent`
- `RegulationRegisteredEvent`, `RegulationUpdatedEvent`, `RegulationRemovedEvent`
- `RegulationJurisdictionAddedEvent`, `RegulationJurisdictionRemovedEvent`
- `JurisdictionRegisteredEvent`, `JurisdictionUpdatedEvent`, `JurisdictionRemovedEvent`
- `SubjectRegisteredEvent`, `SubjectUpdatedEvent`, `SubjectRemovedEvent`
- `SubjectDataDeletedEvent`, `SubjectAuditDeletedEvent`, `SubjectShreddedEvent`

**Platform Core**
- `TenantRegisteredEvent`, `TenantUpdatedEvent`, `TenantSuspendedEvent`, `TenantDeletedEvent`
- `TenantOnboardingStarted`, `TenantOnboardingFinished`

**Platform Billing**
- `SubscriptionCreatedEvent`, `SubscriptionUpdatedEvent`, `SubscriptionCanceledEvent`
- `InvoiceCreatedEvent`, `InvoicePaidEvent`
- `PaymentMethodAttachedEvent`, `PaymentMethodDetachedEvent`

**Platform IAM**
- `UserInvitedEvent`, `UserRegisteredEvent`, `UserUpdatedEvent`, `UserRemovedEvent`
- `RoleAssignedEvent`, `RoleRemovedEvent`

## Event Publishing Flow

```java
// 1. Command service creates a semantic event
var event = new ConsentGrantedEvent(subject, entity, field, category, purpose, jurisdiction);

// 2. Wraps it in a PublishDomainEventInput
var input = PublishDomainEventInput.builder()
    .tenantId(TenantContext.tenantId())
    .eventType(ConsentEventTypes.CONSENT_GRANTED)
    .aggregateKey(new AggregateKey("SubjectConsent", subjectId, tenantId))
    .payload(event)
    .build();

// 3. EventService persists to the event store
eventService.publishEvent(input);
// → INSERT INTO t_{tenantId}.event (aggregate_type, aggregate_id, event_type, payload, version)
// → The outbox poller picks up the event and delivers it to Redis Streams
```

## Event Store Schema

```sql
CREATE TABLE event (
    event_id     BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,           -- Semantic event payload
    version        BIGINT NOT NULL,          -- Optimistic concurrency
    created_at     TIMESTAMP DEFAULT NOW(),
    UNIQUE (aggregate_type, aggregate_id, version)
);
```

## Redis Stream Routing

Events are routed to Redis Streams by scope:

| Stream Key | Events |
|------------|--------|
| `cveil:stream:tenant:{tenantId}` | All tenant-scoped events (consent, catalogue, governance) |
| `cveil:stream:platform` | All platform-scoped events (core, billing, IAM) |

## Outbox Pattern

The outbox poller runs as a scheduled task (`@Scheduled`) that:

1. Reads undelivered events from the `event` table (ordered by `event_id`)
2. Publishes each event to the appropriate Redis Stream via `XADD`
3. Marks the event as delivered

This ensures **at-least-once delivery** — projections must be idempotent.

## Projection Handlers

Each projection handler implements a `StreamEventHandler` functional interface:

```java
@FunctionalInterface
public interface StreamEventHandler {
    void onEvent(String streamKey, String messageId, Map<String, String> fields);
}
```

### Tenant Projections

| Projection | Read Model | Purpose |
|-----------|------------|---------|
| `ConsentAdminProjectionHandler` | PostgreSQL (jOOQ) | Materializes consent_policy rows for admin queries |
| `ConsentDecisionProjectionHandler` | Redis (Hash-per-subject) | Materializes consent graph for O(1) evaluation |

### Platform Projections

| Projection | Read Model | Purpose |
|-----------|------------|---------|
| `CoreProjectionHandler` | PostgreSQL (jOOQ) | Tenant, API key, IdP config read models |
| `BillingProjectionHandler` | PostgreSQL (jOOQ) | Subscription, invoice, payment method read models |
| `IamProjectionHandler` | PostgreSQL (jOOQ) | User, role, invitation read models |

## CQRS Dispatch Layer

The `cveil-core` module provides a generic dispatch infrastructure:

```
CommandDispatcher                    QueryDispatcher
    │                                    │
    ▼                                    ▼
CommandActuator<TCommand, TResult>   QueryActuator<TQuery, TResult>
    │                                    │
    ▼                                    ▼
Domain Service Implementation        Read-Model Query Implementation
```

The `InstrumentedDispatcher` wraps dispatchers with metrics collection (timing, error counting) for observability.

## Aggregate Hydration

For commands that need to validate against current state, **hydration repositories** load the current aggregate from the event store by replaying events:

```
publishEvent(input)
  → EventService.getVersion(aggregateKey)  // optimistic concurrency check
  → INSERT event with version = currentVersion + 1
  → ON CONFLICT → retry with fresh version
```

## Eventual Consistency Model

The system is **eventually consistent** between the command (write) and query (read) sides:

- **Consent writes** are immediately persisted to the event store
- **Consent admin queries** read from the jOOQ projection (milliseconds behind)
- **Consent evaluations** read from the Redis projection (sub-millisecond behind)
- **Consent graph invalidation** propagates through the stream pipeline

For the consent evaluation hot path, the Redis projection is optimized for real-time consistency — the stream consumer processes events within milliseconds of publication.
