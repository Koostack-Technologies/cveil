# CVEIL Repository - Comprehensive Search Results

## Executive Summary

This document contains detailed findings from a comprehensive search of the CVEIL repository covering:
1. Personal Access Token (PAT) / API Key handling
2. Billing, metering, and usage tracking
3. Endpoint categorization mechanisms  
4. Specific endpoint groups (Governance, Consent, Subject, Catalogue)
5. OpenAPI specification generation

---

## 1. PERSONAL ACCESS TOKEN (PAT) / API KEY HANDLING

### Key Findings:
**Location:** `/cveil-rest-api/src/main/java/com/koostack/cveil/rest/filter/ApiKeyAuthFilter.java`

#### Implementation Details:
- **Authentication Filter:** `ApiKeyAuthFilter` (implements `ContainerRequestFilter`)
  - Runs at `Priorities.AUTHENTICATION` (priority 1000)
  - Handles both API keys and JWT tokens
  
- **Token Types Supported:**
  1. **Bootstrap Key:** Single super-admin key configured via `cveil.super-admin.bootstrap-key`
  2. **API Keys (cveil_ prefix):** CRC32 checksum validated, SHA-256 hashed, stored in `platform_iam.access_token` table
  3. **JWT Tokens:** Three dot-separated segments, verified via tenant's JWKS provider (Keycloak by default)

- **Authentication Flow:**
  ```
  1. Extract "Authorization: Bearer <token>" header
  2. Check if token matches bootstrap key (with lifecycle validation)
  3. For cveil_ tokens: Validate CRC32, compute SHA-256, lookup in DB
  4. For JWT tokens: Delegate to tenant IdpProvider for JWKS verification
  5. Set JAX-RS SecurityContext with roles/permissions
  6. Store IdentityContext as request property
  ```

- **Request Property Storage:**
  ```java
  public static final String IDENTITY_CONTEXT_PROPERTY = "cveil.identity-context";
  ```
  Used by `TenantScopeInterceptor` to bind tenant context

- **Bearer Prefix:**
  ```java
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String AUTH_SCHEME_BEARER = "BEARER";
  ```

### Token Management Endpoints (in `PlatformIamResource`):
- **POST** `/api/v1/platform/iam/me/tokens` - Create access token
- **GET** `/api/v1/platform/iam/me/tokens` - List access tokens by creator
- **DELETE** `/api/v1/platform/iam/me/tokens/{tokenPrefix}` - Revoke access token

### Related Components:
- `AccessTokenGenerator` - Generates tokens (cveil-core)
- `CreateAccessTokenCommandActuator` - Command handler (cveil-iam-command)
- `RevokeAccessTokenCommandActuator` - Revocation handler
- `ApiKeyTokenVerifier` - Verifies hashed tokens (cveil-platform-jooq)
- `TokenValidationService` - Core token validation service
- `BootstrapKeyLifecycleService` - Manages bootstrap key state (auto-disable after first API key creation)

---

## 2. BILLING / METERING / USAGE TRACKING

### Billing Endpoints (`/api/v1/billing`):
1. **GET** `/stripe-config` - Get Stripe configuration
2. **POST** `/setup-intent` - Create Stripe setup intent
3. **GET** `/payment-methods` - List payment methods
4. **POST** `/payment-methods/{paymentMethodId}/attach` - Attach payment method
5. **DELETE** `/payment-methods/{paymentMethodId}` - Remove payment method
6. **PUT** `/payment-methods/{paymentMethodId}/default` - Set default payment method
7. **GET** `/invoices` - List invoices
8. **GET** `/invoices/{invoiceId}` - Get invoice details
9. **GET** `/plans` - List subscription plans
10. **GET** `/subscription` - Get current subscription
11. **POST** `/subscription` - Create subscription
12. **PUT** `/subscription` - Update subscription (change plan)
13. **DELETE** `/subscription` - Cancel subscription

### Rate Limiting & Usage Tracking:

#### Rate Limit Filter (`RateLimitFilter`):
**Location:** `/cveil-rest-api/src/main/java/com/koostack/cveil/rest/filter/RateLimitFilter.java`
- Priority: `Priorities.AUTHORIZATION` (2000)
- Implements: `ContainerRequestFilter` + `ContainerResponseFilter`

**Three Dimensions of Rate Limiting:**
1. **Burst Limit** (all endpoints)
   - Per-minute request cap
   - Redis key pattern: `ratelimit:{tenantId}:burst:{YYYYMMddHHmm}`
   - TTL: 120 seconds
   - Header: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

2. **Monthly Evaluation Limit** (evaluation endpoints only)
   - Per-calendar-month cap on consent evaluations
   - Redis key pattern: `ratelimit:{tenantId}:eval:{YYYY-MM}`
   - TTL: 35 days
   - Based on tenant's subscription plan (`eval_limit_monthly`)

3. **Subject Limit** (distinct data subjects monthly)
   - Uses HyperLogLog for cardinality
   - Metric name: `consent_subjects`

**Response Headers:**
```
X-RateLimit-Limit: <burst limit>
X-RateLimit-Remaining: <remaining requests in window>
X-RateLimit-Reset: <unix epoch seconds when window resets>
X-RateLimit-Warning: "approaching limit" (at 90% of monthly eval quota)
Retry-After: <seconds until reset> (only on 429 responses)
```

#### Billing Events (15 event types):
Located in `cveil-core/src/main/java/com/koostack/cveil/core/platform/billing/events/`:
- BillingProfileCreatedEvent
- PaymentMethodAttachedEvent, PaymentMethodDetachedEvent, PaymentMethodStatusUpdatedEvent, PaymentMethodDefaultChangedEvent
- SubscriptionCreatedEvent, SubscriptionPlanChangedEvent, SubscriptionRenewedEvent, SubscriptionCancelledEvent
- InvoiceCreatedEvent, InvoiceUpdatedEvent, InvoicePaidEvent
- UsageReportedEvent
- BillingEmailUpdatedEvent, BillingTaxIdUpdatedEvent, PaymentProcessorSetEvent

#### Stripe Integration:
- `StripePaymentProcessorService` - Handles Stripe webhook processing and payment operations

---

## 3. ENDPOINT CATEGORIZATION MECHANISM

### OpenAPI Tags-Based Categorization:

**File:** `/cveil-rest-api/src/main/java/com/koostack/cveil/rest/config/CveilOpenApiConfig.java`

#### Tag Constants:
```java
TAG_CONSENT = "Consent"           // Consent operations
TAG_CATALOGUE = "Catalogue"       // Data catalogue (fields, categories, entities)
TAG_GOVERNANCE = "Governance"     // Governance framework
TAG_ADMIN = "Admin"               // Event streams and cache invalidation
TAG_PLATFORM = "Platform"         // Platform administration
TAG_IAM = "IAM"                   // Identity & Access Management
TAG_BILLING = "Billing"           // Billing and payments
TAG_HEALTH = "Health"             // Health checks (public)
```

**Security Scheme:**
```java
SECURITY_SCHEME = "BearerAuth"
Type: HTTP Bearer
Format: API Key
```

#### Permission-Based Endpoint Categorization:

Instead of explicit annotations, endpoints are implicitly categorized through **permission requirements** defined in `Permissions.java`:

**File:** `/cveil-rest-api/src/main/java/com/koostack/cveil/rest/auth/Permissions.java`

```java
// Consent permissions
CONSENT_GRANT = "consent:grant"
CONSENT_REVOKE = "consent:revoke"
CONSENT_EVALUATE = "consent:evaluate"
CONSENT_READ = "consent:read"
CONSENT_EXPORT = "consent:export"
CONSENT_DSAR = "consent:dsar"
CONSENT_GRAPH_READ = "consent:graph:read"

// Catalogue permissions
CATALOGUE_READ = "catalogue:read"
CATALOGUE_WRITE = "catalogue:write"

// Governance permissions
GOVERNANCE_READ = "governance:read"
GOVERNANCE_WRITE = "governance:write"

// Events permissions
EVENTS_READ = "events:read"

// Organization permissions
ORG_READ = "org:read"
ORG_WRITE = "org:write"
ORG_MEMBER_MANAGE = "org:member:manage"
ORG_ROLE_MANAGE = "org:role:manage"

// IAM permissions
IAM_USER_READ = "iam:user:read"
IAM_USER_MANAGE = "iam:user:manage"

// Platform permissions
PLATFORM_TENANT_READ = "platform:tenant:read"
PLATFORM_TENANT_MANAGE = "platform:tenant:manage"
```

**Enforcement Method:**
```java
Permissions.require(authz, "consent:grant")      // Single permission
Permissions.requireAny(authz, perm1, perm2)      // Any of multiple
Permissions.requireAll(authz, perm1, perm2)      // All of multiple
```

Super-admin bypass: All permissions checked via `authz.isSuperAdmin()`

---

## 4. SPECIFIC ENDPOINT GROUPS

### A. GOVERNANCE ENDPOINTS (`/api/v1/governance`)

**Base Path:** `/api/v1/governance`
**Resource:** `GovernanceResource`
**Tag:** `TAG_GOVERNANCE`
**Required Permissions:** `governance:read` or `governance:write`

#### Purposes:
- **GET** `/purposes` - List all purposes
- **GET** `/purposes/{purposeName}` - Get single purpose
- **POST** `/purposes` - Register/create purpose (upsert)
- **DELETE** `/purposes/{purposeName}` - Remove purpose
- **POST** `/purposes/policies` - Assign regulations to purpose
- **POST** `/purposes/regulations` - Create policy assignment

#### Regulations:
- **GET** `/regulations` - List all regulations
- **GET** `/regulations/{regulationName}` - Get single regulation
- **POST** `/regulations` - Register regulation
- **DELETE** `/regulations/{regulationName}` - Remove regulation
- **POST** `/regulations/jurisdictions` - Assign jurisdiction to regulation
- **GET** `/regulations/{regulationName}/jurisdictions/{jurisdictionName}` - Get assignment details
- **DELETE** `/regulations/{regulationName}/jurisdictions/{jurisdictionName}` - Remove jurisdiction

#### Jurisdictions:
- **GET** `/jurisdictions` - List all jurisdictions
- **GET** `/jurisdictions/{jurisdictionName}` - Get single jurisdiction
- **POST** `/jurisdictions` - Register jurisdiction
- **DELETE** `/jurisdictions/{jurisdictionName}` - Remove jurisdiction
- **POST** `/jurisdictions/{jurisdictionName}/regulations` - Link regulations to jurisdiction
- **PUT** `/jurisdictions/{jurisdictionName}` - Update jurisdiction

---

### B. CONSENT ENDPOINTS (`/api/v1/subjects`)

**Base Path:** `/api/v1/subjects`
**Resource:** `ConsentResource`
**Tag:** `TAG_CONSENT`
**Required Permissions:** Various consent-specific permissions

#### Consent Operations (Mutating - require `consent:write`):
- **POST** `/consent/grant` - Grant consent for single scope
- **POST** `/consent/revoke` - Revoke consent for single scope
- **POST** `/consent/grant-all` - Grant consent for multiple scopes (batch)
- **POST** `/consent/revoke-all` - Revoke consent for multiple scopes (batch)

#### Consent Evaluation (require `consent:evaluate`):
- **POST** `/{subjectId}/evaluate` - Evaluate consent for specific subject/scope
  - Monthly limit enforced via RateLimitFilter
  - Publishes `consent_evaluations` metric

#### Consent Query & Reporting (require `consent:read`):
- **GET** `/` - List all subjects
- **GET** `/{name}` - Get subject details
- **GET** `/{id}/summary` - Get consent summary for subject
- **GET** `/{id}/history` - Get consent audit history
- **GET** `/{id}/export` - Export subject data (require `consent:export`)

#### Subject Management:
- **POST** `/{name}` - Register subject (require `consent:read`)
- **PUT** `/{name}` - Update subject metadata (require `consent:read`)
- **DELETE** `/{name}` - Remove subject (require `consent:read`)

#### Data Subject Access Requests (DSAR):
- **GET** `/{id}/audit` - Get DSAR audit trail (require `consent:dsar`)
- **DELETE** `/{id}` - Delete subject data/shred (require `consent:dsar`)
- **DELETE** `/{name}/dsar-key` - Remove DSAR encryption key (require `consent:dsar`)

---

### C. CATALOGUE ENDPOINTS (`/api/v1/catalogue`)

**Base Path:** `/api/v1/catalogue`
**Resource:** `CatalogueResource`
**Tag:** `TAG_CATALOGUE`
**Required Permissions:** `catalogue:read` or `catalogue:write`

#### Fields:
- **GET** `/fields` - List all registered fields (require `catalogue:read`)
- **POST** `/fields` - Register sensitive field/entity pair (require `catalogue:write`)
- **DELETE** `/fields/{entityName}/{fieldName}` - Remove field (require `catalogue:write`)

#### Field-Category Assignments:
- **POST** `/fields/categories` - Assign category to field (require `catalogue:write`)
- **DELETE** `/fields/{entityName}/{fieldName}/categories/{categoryName}` - Unassign category (require `catalogue:write`)

#### Entities:
- **GET** `/entities` - List all entities (require `catalogue:read`)
- **GET** `/entities/{entityName}` - Get entity details (require `catalogue:read`)
- **GET** `/entities/{entityName}/fields` - List entity's fields (require `catalogue:read`)
- **POST** `/entities` - Register entity (require `catalogue:write`)
- **PUT** `/entities/{entityName}` - Update entity (require `catalogue:write`)
- **DELETE** `/entities/{entityName}` - Remove entity (require `catalogue:write`)

#### Categories:
- **GET** `/categories` - List all categories (require `catalogue:read`)
- **GET** `/categories/{categoryName}` - Get category details (require `catalogue:read`)
- **POST** `/categories` - Register category (require `catalogue:write`)
- **PUT** `/categories/{categoryName}` - Update category (require `catalogue:write`)
- **DELETE** `/categories/{categoryName}` - Remove category (require `catalogue:write`)

---

### D. SUBJECT ENDPOINTS (DSAR/Data Management)

**Included in ConsentResource** (`/api/v1/subjects`)

These are the data subject management endpoints:

#### Subject Lifecycle:
- **POST** `/{name}` - Register new subject
- **GET** `/{name}` - Retrieve subject
- **PUT** `/{name}` - Update subject
- **DELETE** `/{name}` - Remove subject permanently
- **GET** `/{id}/dsar-key` - Get DSAR encryption key
- **DELETE** `/{name}/dsar-key` - Revoke DSAR encryption key

#### Data Subject Rights (GDPR/CCPA):
- **GET** `/{id}/export` - Export all subject data (right to portability)
- **DELETE** `/{id}` - Delete all subject data (right to erasure)
- **GET** `/{id}/audit` - Get DSAR audit log

---

## 5. OPENAPI SPEC GENERATION

**Location:** `/cveil-rest-api/`

### OpenAPI Configuration:

**Main Config:** `CveilOpenApiConfig.java`
- Annotation: `@OpenAPIDefinition`
- Framework: **Eclipse MicroProfile OpenAPI** (SmallRye OpenAPI)
- Spec Version: 1.0.0 (API version)

**OpenAPI Spec Endpoint:**
```
GET /q/openapi
```
Returns YAML/JSON OpenAPI specification generated from annotations

**Built-in Swagger UI (Disabled):**
```
GET /q/swagger-ui
```
Disabled in favor of custom documentation endpoints

### Custom Documentation Endpoints:

**File:** `OpenApiSplitService.java`
- Purpose: Splits monolithic OpenAPI spec into tenant-scoped and platform-scoped specs
- Fetches: `/q/openapi` spec
- Filters by tag (TAG_CONSENT, TAG_GOVERNANCE, TAG_CATALOGUE, etc.)

**Platform Documentation Resource:**
- **GET** `/api/v1/platform/docs` - Platform admin OpenAPI spec
- **File:** `PlatformDocsResource.java`

**Tenant Documentation Resource:**
- **GET** `/api/v1/tenants/{tenantId}/docs` - Tenant-specific OpenAPI spec
- **File:** `TenantDocsResource.java`

### OpenAPI Annotations Used:

Throughout resource classes:
- `@OpenAPIDefinition` - Top-level API definition
- `@Components` - Shared security schemes
- `@SecurityScheme` - Bearer token scheme
- `@Tag` - Endpoint grouping/organization
- `@Operation` - Individual endpoint documentation
- `@APIResponse` / `@APIResponses` - HTTP response documentation
- `@Parameter` - Request parameter documentation
- `@Content` / `@Schema` - Request/response body schemas
- `@SecurityRequirement` - Required authentication scheme per endpoint

### Application Configuration:

**File:** `application.yml`
```yaml
quarkus:
  openapi:
    path: /q/openapi  # OpenAPI spec endpoint
    expose: "true"
  swagger-ui:
    # Built-in Swagger UI disabled; custom docs at /api/v1/platform/docs
```

### API Documentation Generation:

**Build-time:** SmallRye OpenAPI reads annotations during Quarkus build
**Runtime:** Generated spec served at `/q/openapi`
**Custom:** Split service filters and reorganizes spec by tenant scope

---

## Summary Table

| Category | Implementation | Key Files | Endpoints |
|----------|----------------|-----------|-----------|
| **Token Auth** | JWT + API Key (cveil_ prefix) | `ApiKeyAuthFilter`, `TokenValidationService` | `/api/v1/platform/iam/me/tokens` (3 endpoints) |
| **Rate Limiting** | Redis-backed per-minute/per-month | `RateLimitFilter`, `RateLimitService` | Built into all endpoints |
| **Billing** | Stripe integration with monthly tracking | `BillingResource`, `StripePaymentProcessorService` | `/api/v1/billing` (13 endpoints) |
| **Governance** | Purposes, Regulations, Jurisdictions | `GovernanceResource` | `/api/v1/governance` (11+ endpoints) |
| **Consent** | Grant/Revoke/Evaluate with DSAR | `ConsentResource` | `/api/v1/subjects` (15+ endpoints) |
| **Catalogue** | Fields, Entities, Categories | `CatalogueResource` | `/api/v1/catalogue` (11+ endpoints) |
| **Subjects** | Data subject management & DSAR | `ConsentResource` (nested) | `/api/v1/subjects` (8+ endpoints) |
| **OpenAPI** | MicroProfile/SmallRye with split docs | `OpenApiSplitService`, `CveilOpenApiConfig` | `/q/openapi`, `/api/v1/*/docs` |

