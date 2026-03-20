# Core SPI Architecture

## Overview

The `cveil-core` module is the **framework-agnostic foundation** of the entire system. It defines all domain interfaces, models, events, aggregates, and exceptions as pure Java code with no runtime framework dependencies (no Quarkus, no Spring, no jOOQ). The only external dependency is Jackson for JSON serialization.

This design ensures that the core domain logic can be consumed by any runtime adapter — the Quarkus REST API, an embedded Java library, a test harness, or a future alternative runtime.

## Package Structure

```
com.koostack.cveil.core/
├── Cveil.java                    # Entry-point facade
├── CveilService.java             # Top-level service interface
├── PlatformConstants.java        # Shared constant values
│
├── spi/                          # Service Provider Interface system
│   ├── Plugin.java               #   Base plugin interface (configure + destroy)
│   ├── PluginBundle.java         #   Groups related plugins for wiring
│   ├── PluginConfig.java         #   Marker for plugin configuration types
│   ├── Configurable.java         #   Lifecycle: configure(T config)
│   ├── NamedPlugin.java          #   Plugin with a unique name
│   └── Prioritized.java          #   Plugin ordering support
│
├── cqrs/                         # CQRS dispatch infrastructure
│   ├── CommandDispatcher.java    #   Routes commands to actuators
│   ├── QueryDispatcher.java      #   Routes queries to actuators
│   ├── CommandActuator.java      #   Command handler interface
│   ├── QueryActuator.java        #   Query handler interface
│   ├── Actuator.java             #   Base actuator type
│   ├── InstrumentedDispatcher.java # Wraps dispatchers with metrics
│   └── AbstractOrchestrator.java #   Multi-step saga base class
│
├── event/                        # Event sourcing core
│   ├── EventService.java         #   Unified event publish/replay SPI
│   ├── DomainEvent.java          #   Base domain event record
│   ├── EventListener.java        #   Event subscription interface
│   ├── Shreddable.java           #   Marker for GDPR-shreddable events
│   └── models/                   #   Event payload models
│       ├── PublishDomainEventInput.java
│       ├── AggregateSnapshotTrigger.java
│       ├── PlatformEvent.java
│       ├── TenantEvent.java
│       ├── ConsentGranted.java
│       ├── ConsentRevoked.java
│       ├── TenantOnboardingStarted.java
│       └── TenantOnboardingFinished.java
│
├── models/                       # Shared domain models
│   ├── AggregateRoot.java        #   Base aggregate with version tracking
│   ├── AggregateKey.java         #   Aggregate identity (type + id + tenantId)
│   ├── TenantContext.java        #   ScopedValue-based tenant binding
│   ├── RequestContext.java       #   Request metadata (tracing, identity)
│   ├── IdentityContext.java      #   Authenticated user identity
│   ├── AuthorizationContext.java #   Authorization info (roles, permissions)
│   ├── AuthResult.java           #   Authentication result wrapper
│   ├── ConsentScope.java         #   Four-dimensional consent key
│   ├── ConsentResponse.java      #   Consent evaluation result
│   ├── DecisionDetail.java       #   Detailed consent decision metadata
│   ├── BatchConsentEntry.java    #   Batch consent operation input
│   ├── BatchConsentResult.java   #   Batch consent operation result
│   ├── ErasureReceipt.java       #   DSAR deletion receipt
│   ├── DiscoveredField.java      #   @Sensitive annotation discovery
│   ├── DiscoveryResult.java      #   Bulk discovery result
│   └── *View.java                #   Read-model view records (Category, Entity, Field, etc.)
│
├── annotation/                   # Code-level consent annotations
│   ├── @ConsentRequired          #   Marks methods requiring consent check
│   └── @Sensitive                #   Marks fields as PII for discovery
│
├── service/                      # Core service interfaces
│   ├── ConsentDecisionService.java # High-level consent evaluation
│   ├── AuditLogger.java         #   Audit logging interface
│   ├── MaskingStrategy.java      #   Field masking strategy interface
│   └── SensitiveFieldResolver.java # @Sensitive field resolution
│
├── sdk/                          # SDK integration interfaces
│   ├── IdentityResolver.java     #   Subject identity resolution
│   └── SensitiveDiscovery.java   #   @Sensitive scanning interface
│
├── registry/
│   └── PluginRegistry.java       #   Runtime plugin lookup
│
├── platform/                     # Platform (control plane) SPIs
│   ├── core/                     #   Tenant management domain
│   │   ├── CoreCommandService.java
│   │   ├── CoreQueryService.java
│   │   ├── CoreProjection.java
│   │   ├── CoreEventTypes.java
│   │   └── models/
│   ├── billing/                  #   Billing domain
│   │   ├── BillingCommandService.java
│   │   ├── BillingQueryService.java
│   │   ├── BillingProjection.java
│   │   ├── PaymentProcessorService.java
│   │   ├── BillingEventTypes.java
│   │   └── models/
│   ├── iam/                      #   Identity & access management
│   │   ├── IamCommandService.java
│   │   ├── IamQueryService.java
│   │   ├── IdpProvider.java
│   │   ├── TokenValidationService.java
│   │   ├── AccessTokenGenerator.java
│   │   ├── PermissionService.java
│   │   ├── EmailService.java
│   │   └── IamEventTypes.java
│   └── plan/                     #   Plans & feature gates
│       ├── PlanQueryService.java
│       ├── FeatureGateService.java
│       └── PlanFeature.java
│
├── tenant/                       # Tenant (data plane) SPIs
│   ├── consent/                  #   Consent management domain
│   │   ├── ConsentAdminCommandService.java
│   │   ├── ConsentAdminQueryService.java
│   │   ├── ConsentAdminProjection.java
│   │   ├── ConsentGraphQueryService.java
│   │   ├── SubjectDsarService.java
│   │   ├── ExpirationSweeperService.java
│   │   ├── ConsentEventTypes.java
│   │   ├── aggregates/          #   Domain aggregates
│   │   │   ├── SubjectConsentAggregate.java
│   │   │   ├── CategoryAggregate.java
│   │   │   ├── EntityAggregate.java
│   │   │   ├── FieldAggregate.java
│   │   │   ├── PurposeAggregate.java
│   │   │   ├── RegulationAggregate.java
│   │   │   ├── JurisdictionAggregate.java
│   │   │   ├── SubjectAggregate.java
│   │   │   └── DsarAggregate.java
│   │   ├── events/              #   Semantic domain events (40+ event types)
│   │   │   ├── ConsentGrantedEvent.java
│   │   │   ├── ConsentRevokedEvent.java
│   │   │   ├── ConsentExpiredEvent.java
│   │   │   ├── CategoryRegisteredEvent.java
│   │   │   ├── EntityRegisteredEvent.java
│   │   │   ├── FieldRegisteredEvent.java
│   │   │   ├── PurposeRegisteredEvent.java
│   │   │   ├── RegulationRegisteredEvent.java
│   │   │   ├── JurisdictionRegisteredEvent.java
│   │   │   └── ... (Updated, Removed, Snapshot events)
│   │   └── invalidation/
│   │       └── InvalidationSink.java
│   ├── authz/
│   │   └── AuthzProvider.java    #   Tenant authorization interface
│   ├── iam/
│   │   ├── TenantIamEventTypes.java
│   │   └── TenantRoles.java
│   └── user/
│       ├── UserCommandService.java
│       ├── UserQueryService.java
│       └── UserProjection.java
│
├── metrics/                      # Observability interfaces
│   ├── Metric.java
│   ├── MetricType.java
│   ├── MetricPublisherService.java
│   ├── MetricProjection.java
│   └── MetricsSweeper.java
│
├── query/
│   └── RetryableQuery.java       # Retry-safe query wrapper
│
├── exception/                    # Domain exception hierarchy
│   ├── CveilException.java       #   Base exception
│   ├── CveilServiceException.java
│   ├── CveilAuthException.java
│   ├── CveilConfigurationException.java
│   ├── CveilRateLimitException.java
│   └── ConsentDeniedException.java
│
└── util/
    └── FieldNameNormalizer.java   # Consistent field name formatting
```

## Plugin System

The plugin system allows runtime adapters to be swapped without changing domain logic:

```java
// Plugin interface — all service implementations extend this
public interface Plugin<T> extends Configurable<T> {
    void configure(T config);
    void destroy();
}

// PluginBundle groups related plugins
public interface PluginBundle {
    void registerPlugins(PluginRegistry registry);
}
```

**Production wiring** is done in `cveil-bundle-core`, which registers jOOQ, Redis, and Keycloak implementations. The REST API layer (`CveilProducers`) creates CDI beans from these plugins.

## TenantContext (Java 21 ScopedValue)

Tenant isolation is achieved via `ScopedValue`, a Java 21 preview feature that provides structured, thread-safe context propagation — critical for virtual threads where `ThreadLocal` can leak across tasks:

```java
public final class TenantContext {
    private static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();

    public static void runWhere(String tenantId, Runnable action) {
        ScopedValue.runWhere(TENANT_ID, tenantId, action);
    }

    public static String tenantId() {
        return TENANT_ID.get();
    }
}
```

Every database operation, Redis operation, and event publication reads `TenantContext.tenantId()` to scope data access to the correct tenant.

## Aggregate Design

Aggregates follow DDD principles with version tracking for optimistic concurrency:

```java
public abstract class AggregateRoot {
    private long version;
    // Subclasses define domain state and behavior
}

// Identity for any aggregate
public record AggregateKey(String aggregateType, String aggregateId, String tenantId) {}
```

The consent domain defines nine aggregate types:
- `SubjectConsentAggregate` — per-subject consent state
- `CategoryAggregate`, `EntityAggregate`, `FieldAggregate` — data catalogue
- `PurposeAggregate`, `RegulationAggregate`, `JurisdictionAggregate` — governance
- `SubjectAggregate`, `DsarAggregate` — subject management and DSAR tracking
