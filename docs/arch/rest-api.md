# REST API Architecture

## Overview

The `cveil-rest-api` module is the **deployment unit** — a Quarkus 3.22 application that serves as the JAX-RS adapter over the framework-agnostic `cveil-core` SPI layer. It runs on **virtual threads** (required for `ScopedValue`-based tenant context) and uses **RESTEasy Reactive** with Jackson for JSON serialization.

## Module Structure

```
cveil-rest-api/src/main/java/com/koostack/cveil/rest/
├── config/                     # CDI configuration and bean producers
│   ├── CveilConfig.java        #   Quarkus @ConfigMapping for cveil.* properties
│   ├── CveilProducers.java     #   CDI @Produces — wires all SPI implementations
│   ├── CveilOpenApiConfig.java #   @OpenAPIDefinition and tag constants
│   ├── CveilHealthService.java #   SmallRye Health integration
│   └── PlatformCqrsHolder.java #   Holds platform CQRS dispatcher instances
│
├── resource/                   # JAX-RS resource classes (REST endpoints)
│   ├── ConsentResource.java    #   /api/v1/consent/*
│   ├── CatalogueResource.java  #   /api/v1/catalogue/*
│   ├── GovernanceResource.java #   /api/v1/governance/*
│   ├── DsarResource.java       #   /api/v1/dsar/*
│   ├── GraphResource.java      #   /api/v1/consent/graph/*
│   ├── PlatformResource.java   #   /api/v1/platform/*
│   ├── PlatformIamResource.java #  /api/v1/platform/iam/*
│   ├── BillingResource.java    #   /api/v1/platform/billing/*
│   ├── PlatformMetricsResource.java # /api/v1/platform/metrics/*
│   ├── TenantMetricsResource.java   # /api/v1/metrics/*
│   ├── WebhookManagementResource.java # /api/v1/webhooks/*
│   ├── WebhookResource.java    #   Inbound webhook receiver (Stripe)
│   ├── ReportResource.java     #   /api/v1/reports/*
│   ├── BannerConfigResource.java #  /api/v1/banner-config
│   ├── PublicConsentResource.java # /public/consent/* (unauthenticated)
│   ├── PublicDsarResource.java #   /public/dsar/* (unauthenticated)
│   └── HealthResource.java     #   /api/v1/health
│
├── docs/                       # Split API documentation
│   ├── OpenApiSplitService.java #  Splits OpenAPI spec into tenant/platform
│   ├── TenantDocsResource.java  #  Serves /api/v1/docs (Swagger UI)
│   └── PlatformDocsResource.java # Serves /api/v1/platform/docs
│
├── dto/                        # REST-layer data transfer objects
│   ├── CommonDtos.java         #   ErrorResponse, HealthResponse, MessageResponse
│   ├── ConsentDtos.java        #   SubjectDataExport, DsarDeletionReceipt, etc.
│   ├── ConsentScopeDto.java    #   Consent scope input DTO
│   ├── AdminDtos.java          #   Catalogue and admin DTOs
│   ├── BillingDtos.java        #   Billing and subscription DTOs
│   ├── IamDtos.java            #   IAM and user management DTOs
│   ├── PlatformDtos.java       #   Tenant provisioning DTOs
│   └── MetricsDtos.java        #   Metrics response DTOs
│
├── filter/                     # JAX-RS request/response filters
│   ├── RequestTracingFilter.java  # Assigns X-Request-Id, populates MDC
│   ├── ApiKeyAuthFilter.java   #   SHA-256 API key authentication
│   ├── DpopVerifier.java       #   DPoP proof verification (RFC 9449)
│   ├── RateLimitFilter.java    #   Redis-based burst + monthly rate limiting
│   ├── BillableMetricFilter.java # Records API usage for billing
│   └── FeatureGateFilter.java  #   Plan-based feature access control
│
├── interceptor/                # CDI interceptors
│   ├── TenantScopeInterceptor.java # Binds TenantContext via ScopedValue
│   └── @TenantScoped            #   Interceptor binding annotation
│
├── gate/
│   └── FeatureGateChecker.java  # Evaluates feature flags per plan tier
│
├── auth/                       # Authentication and authorization
│   ├── Permissions.java         # Permission constants
│   └── Roles.java               # Role constants
│
├── billing/
│   └── BillableEndpoints.java   # Marks endpoints as billable
│
├── context/
│   └── CveilRequestHolder.java  # Request-scoped context holder
│
├── orchestrator/               # Multi-step orchestration sagas
│   ├── RegisterOrganizationOrchestrator.java  # Tenant onboarding flow
│   └── InviteOrgMemberOrchestrator.java       # Member invitation flow
│
├── onboarding/                 # Tenant onboarding
│   ├── IndustryPreset.java     #   Pre-built industry configurations
│   └── OnboardingStateHolder.java # Tracks onboarding step progress
│
├── lifecycle/                  # Application lifecycle tasks
│   ├── BootstrapKeyLifecycleService.java  # Bootstrap key provisioning
│   ├── DeferredProvisioningTask.java      # Deferred tenant provisioning
│   ├── ProvisioningTracker.java           # Tracks provisioning state
│   ├── MetricsSweeperScheduler.java       # Periodic metrics aggregation
│   ├── StripePlanSyncTask.java            # Syncs plans from Stripe
│   ├── StripeReconciliationTask.java      # Reconciles billing state
│   └── WebhookRetryTask.java             # Retries failed webhooks
│
└── site/
    └── SiteResource.java        # Serves landing pages (Qute templates)
```

## CDI Bean Wiring (CveilProducers)

`CveilProducers` is the central CDI bean factory that reads `CveilConfig`, creates infrastructure connections, and produces all SPI implementations as CDI beans:

```
CveilProducers
├── Creates DataSource (PostgreSQL with TLS)
├── Creates Redis client (Jedis with TLS)
├── Creates Keycloak admin client
│
├── @Produces CoreConsentBundle
│   ├── JooqConsentAdminService (consent command/query)
│   ├── ConsentDecisionProjectionHandler (Redis graph)
│   └── ConsentAdminProjectionHandler (jOOQ read model)
│
├── @Produces Platform CQRS services
│   ├── CoreCommandService / CoreQueryService / CoreProjection
│   ├── BillingCommandService / BillingQueryService / BillingProjection
│   ├── IamCommandService / IamQueryService
│   ├── PlanQueryService / FeatureGateService
│   ├── PaymentProcessorService (Stripe)
│   └── WebhookOutboundService
│
├── @Produces MetricPublisherService (Redis)
├── @Produces MetricsSweeper
└── @Produces EventService (jOOQ event store)
```

## Authentication Pipeline

The API supports three authentication mechanisms, resolved in order:

1. **Bootstrap Key** — A pre-shared key for initial setup (`opr_bootstrap_*`). Only valid for platform admin operations.
2. **API Key** — Bearer tokens prefixed with `cveil_` or `opr_live_`. The key is SHA-256 hashed and looked up in the `api_key` table.
3. **OIDC + DPoP** — JWT tokens issued by Keycloak with optional DPoP proof binding. The `DpopVerifier` validates the sender-constrained token.

## Rate Limiting

Rate limiting uses atomic Redis Lua scripts to prevent race conditions:

```lua
-- Atomic INCR + EXPIRE in a single round-trip
local curr = redis.call('INCR', KEYS[1])
if curr == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return curr
```

Two rate limit tiers are enforced:
- **Burst** — Short-term (120s window) to prevent abuse
- **Monthly** — Long-term quota per tenant plan tier (35-day TTL for safety)

## OpenAPI Documentation

The API is fully documented with OpenAPI 3.0 annotations. Two separate Swagger UI instances are served:
- `/api/v1/docs` — Tenant API documentation
- `/api/v1/platform/docs` — Platform API documentation

The `OpenApiSplitService` reads the single OpenAPI model and splits it into tenant and platform subsets by tag.

## Error Response Format

All error responses follow a consistent JSON structure:

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Monthly API quota exhausted. Upgrade your plan or wait until the next billing cycle.",
  "retryable": true,
  "retryAfter": 3600
}
```

The `requestId` correlates with the `X-Request-Id` response header for distributed tracing.
