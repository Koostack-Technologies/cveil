# Operio Authentication & Authorization System - Comprehensive Architecture

## Executive Summary

Operio implements a **hybrid authentication and authorization system** using:
- **Keycloak** as the primary Identity Provider (IdP)
- **JWT/OAuth 2.0** for token-based authentication
- **Role-Based Access Control (RBAC)** for authorization
- **ScopedValue-based context** (Java 21+) for tenant and request scoping
- **Per-tenant vs personal access** patterns via API keys and personal access tokens

The system differentiates between:
1. **Personal access**: A user accessing resources as themselves (their personal tenant)
2. **Organizational access**: A user accessing resources on behalf of an organization
3. **Admin access**: Platform-level super-admin privileges

---

## 1. AUTHENTICATION LAYER

### 1.1 Authentication Methods

The `ApiKeyAuthFilter` (priority `Priorities.AUTHENTICATION`) supports 4 authentication mechanisms in order:

#### A. Bootstrap Key (One-Time Setup)
- **Location**: `/operio-rest-api/src/main/java/com/koostack/operio/rest/filter/ApiKeyAuthFilter.java` (line 200-244)
- **Purpose**: Initial platform setup before any real API keys exist
- **Config**: `operio.super-admin.bootstrap-key`
- **Behavior**:
  - Grants `super-admin` role with **no tenant scope** (tenantId = null)
  - Auto-disabled after first API key is created
  - Can be force-enabled via `OPERIO_FORCE_BOOTSTRAP=true` for disaster recovery
  - `BootstrapKeyLifecycleService` manages active/consumed state

#### B. Config-Based API Keys (Dev/Test)
- **Location**: ApiKeyAuthFilter (line 246-260)
- **Config**: `operio.api-keys.*` map in application.yml
- **Format**: `key_value=role:role1,role:role2,tenant:tenant-id`
- **Parsing**: Comma-separated directives for roles and tenant binding

#### C. Personal Access Tokens (cveil_ prefix)
- **Prefix**: `cveil_` (Access Token Generator pattern)
- **Validation** (line 264-417):
  1. Fast-reject via CRC32 checksum validation
  2. SHA-256 hash computation
  3. Lookup in `platform_iam.access_token` table
  4. Status validation (must be "active")
  5. Expiration validation
  6. Role snapshot extraction
- **Metadata**: Carries token name, token prefix in claims
- **Context**: Can bind to specific tenantId or be tenant-agnostic

#### D. JWT Tokens
- **Detection**: Three dot-separated base64url segments (line 271-274)
- **Extraction** (line 438-532):
  - Extract tenant from JWT payload without signature verification
  - Looks for `tenant_id` or `tenantId` claim
  - Falls back to Keycloak Organizations claim `organization`
  - Falls back to issuer path parsing: `/realms/{realmName}`
  - Supports shared platform realm with username-based tenant derivation
- **Verification** (line 573-649): Delegates to tenant's `IdpProvider`

### 1.2 Keycloak Integration

**Keycloak Configuration**:
- **Module**: `common/operio-keycloak/`
- **Version**: Admin Client SDK 26.0.8 with Nimbus JOSE for JWT verification
- **Architecture**: Per-tenant realms (current) → Single realm + Organizations (planned)

**Key Class: `KeycloakAuthSupport`**
- Location: `common/operio-keycloak/src/main/java/com/koostack/operio/common/keycloak/KeycloakAuthSupport.java`
- Operations:
  - **ROPC (Resource Owner Password Credentials)**: Email/password authentication
  - **Refresh Token Grant**: Token refresh from refresh tokens
  - **Logout**: Token revocation via `/protocol/openid-connect/logout`
  - **Client Secret Resolution**: 3-tier strategy:
    1. In-memory cache (`clientSecretCache`)
    2. Static config value
    3. Dynamic Admin API lookup
  - **JWT Verification**: Via `JwksTokenVerifier`

**JWKS Token Verification**:
- Location: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/JwksTokenVerifier.java`
- **Signature Algorithm**: RS256 (RSA with SHA-256)
- **Key Rotation Handling**: Auto-refresh JWKS on `kid` miss, retry once
- **JWKS Caching**: 5-minute TTL per issuer URL (configurable)
- **Role Extraction** (line 106-151):
  ```
  1. Extract from Keycloak realm_access.roles structure
  2. Fallback to standard OIDC 'roles' claim
  3. Combines both for maximum compatibility
  ```

**Per-Tenant Realm Structure**:
```
Keycloak Instance
├── master realm (admin)
├── t_tenant-1
│   ├── operio-backend client (ROPC, service accounts)
│   ├── Users (stored only here)
│   ├── Roles: consent:*, admin:*, tenant-*
│   └── Realm-level JWT with iss=http://keycloak/realms/t_tenant-1
├── t_tenant-2
│   └── ...
└── operio (platform realm — single shared realm for all users)
    ├── operio-backend client
    ├── Platform users
    └── Service accounts
```

### 1.3 Identity Context

**IdentityContext Record** (`operio-core/src/main/java/com/koostack/operio/core/models/IdentityContext.java`):
```java
record IdentityContext(
    String subject,      // OIDC user ID (unique identifier)
    String token,        // Raw bearer token (nullable)
    String email,        // User email (nullable)
    Set<String> roles,   // Authorities from IdP
    Map<String, Object> claims,  // All IdP claims
    String tenantId,     // Tenant scope (nullable — for platform admins)
    String provider      // "bootstrap-key", "config-api-key", "access-token", "keycloak"
)
```

**Stored In Request**: 
- Property: `ApiKeyAuthFilter.IDENTITY_CONTEXT_PROPERTY = "operio.identity-context"`
- Consumed by: `TenantScopeInterceptor` for scoped context binding

---

## 2. AUTHORIZATION LAYER

### 2.1 Role Hierarchy

**API Roles** (for API key holders):
- `consent:read` — Read-only consent operations
- `consent:write` — Mutating consent operations
- `admin:read` — Read-only admin operations
- `admin:write` — Mutating admin operations
- `super-admin` — Platform-level unrestricted access (overrides all checks)

**Tenant Roles** (for human users in an organization):
- `tenant-admin` → implies all API roles + tenant-write + tenant-read
- `tenant-write` → implies consent:*, admin:*, tenant-read
- `tenant-read` → implies consent:read, admin:read

**Location**: `/operio-rest-api/src/main/java/com/koostack/operio/rest/auth/Roles.java`

**Role Hierarchy Map**:
```java
ROLE_HIERARCHY = {
    "tenant-admin": {consent:read, consent:write, admin:read, admin:write, tenant-write, tenant-read},
    "tenant-write": {consent:read, consent:write, admin:read, admin:write, tenant-read},
    "tenant-read":  {consent:read, admin:read}
}
```

### 2.2 Authorization Enforcement

**Helper Methods** (Roles class):
```java
static void requireRole(SecurityContext ctx, String requiredRole)
static void requireAnyRole(SecurityContext ctx, String... roles)
```

**Logic**:
1. If caller has `super-admin` → allow (bypass all checks)
2. Otherwise check effective role via hierarchy
3. Throw `OperioAuthException` → HTTP 403 Forbidden

**Example Usage** (ConsentResource):
```java
@POST @Path("/consent/grant")
public Response grantConsent(@Context SecurityContext sc, GrantRequest request) {
    Roles.requireRole(sc, Roles.CONSENT_WRITE);  // Enforce role
    // ... proceed with grant operation
}
```

### 2.3 Tenant-Scoped Authorization

**AuthzProvider SPI** (operio-core):
- Location: `operio-core/src/main/java/com/koostack/operio/core/tenant/authz/AuthzProvider.java`
- Methods:
  - `void assignRole(tenantId, userId, role)`
  - `void removeRole(tenantId, userId, role)`
  - `Set<String> getUserRoles(tenantId, userId)`

**Keycloak Implementation**:
- Location: `tenant/operio-authz-keycloak/src/main/java/com/koostack/operio/tenant/authz/keycloak/KeycloakAuthzProvider.java`
- Manages realm-level role assignments via Keycloak Admin API
- **Deprecated**: Functionality moved to `TenantIamCommandService` and `TenantIamQueryService`

**Tenant Roles Constants** (`TenantRoles.java`):
```java
public static final String TENANT_ADMIN = "tenant-admin";
public static final String TENANT_WRITE = "tenant-write";
public static final String TENANT_READ = "tenant-read";
```

---

## 3. TENANT & ORGANIZATION CONTEXT

### 3.1 Tenant Context Resolution

**TenantContext** (Java 21 ScopedValue):
- Location: `operio-core/src/main/java/com/koostack/operio/core/models/TenantContext.java`
- **Binding**: No direct setter — uses structured concurrency
- **Accessors**:
  - `static String get()` — returns tenantId or null
  - `static String require()` — throws if null
  - `static boolean isPresent()` — checks if bound
  - `static <R> R callWhere(tenantId, Callable)` — scoped execution
  - `static void runWhere(tenantId, Runnable)` — scoped execution

**Auto-Binding in Interceptor**:
- Location: `operio-rest-api/src/main/java/com/koostack/operio/rest/interceptor/TenantScopeInterceptor.java`
- **Priority**: `PLATFORM_BEFORE + 10` (after platform, before app-level)
- **Logic**:
  ```java
  IdentityContext identity = requestHolder.identity();
  if (identity.tenantId() != null) {
      TenantContext.callWhere(tenantId, () ->
          RequestContext.callWhere(meta, invocation::proceed)
      );
  } else {
      // No tenant scope (bootstrap super-admin)
      RequestContext.callWhere(meta, invocation::proceed);
  }
  ```
- **Activation**: Requires `@TenantScoped` annotation on resource class/method

### 3.2 Personal vs Organization Context

#### Personal Access (Self-Service)
- **User Registration**: `POST /api/v1/platform/iam/users` (PlatformIamResource)
- **Tenant Derivation**: Username → sanitized tenant ID (TenantIdDeriver)
  - Example: `"john.doe"` → `"john_doe"`
  - Max 63 chars, starts with letter, `[a-z0-9_]` only
- **Orchestration** (RegisterUserOrchestrator):
  1. Register user in Keycloak realm
  2. Create personal tenant aggregate
  3. Provision Redis infrastructure
  4. Create billing profile
  5. Create free-plan subscription
- **Result**: User owns single personal organization with derived tenantId
- **Personal Token Creation**: `POST /api/v1/platform/iam/me/tokens`
  - Creates token scoped to `requestHolder.identity().tenantId()` (personal org)
  - Snapshots current caller roles at creation time

#### Organization/Team Access
- **Multi-Organization**: Users can be members of multiple organizations
- **Tenant Context**: Set from API key's tenantId or JWT's extracted tenantId
- **Role Assignment**: Can have different roles in different organizations
- **Membership**: Via Keycloak Organizations (26+) or user attributes
- **Admin Endpoints**: Not yet visible in code (planned feature per KEYCLOAK_ARCHITECTURE.md)

#### Bootstrap/Admin Access
- **Super-Admin Tenant**: `tenantId = null`
- **Scope**: No multi-tenant scope, operates at platform level
- **Permissions**: All endpoints accessible
- **Operations**: Tenant CRUD, API key management, user management

### 3.3 Request Context

**RequestContext** (Java 21 ScopedValue):
- Location: `operio-core/src/main/java/com/koostack/operio/core/models/RequestContext.java`
- **RequestMeta Record**:
  ```java
  record RequestMeta(
      String actor,        // Identity performing action (subject from JWT)
      String ipAddress,    // Originating IP
      String userAgent     // HTTP User-Agent header
  )
  ```
- **Default Actor**: `"system"` when no request scope
- **Accessors**:
  - `static RequestMeta get()` — returns metadata or null
  - `static String actor()` — returns actor (never null)
  - `static String ipAddress()` — returns IP or null
  - `static String userAgent()` — returns UA or null

**Binding**:
- Set by ApiKeyAuthFilter (after auth) via `OperioRequestHolder`
- Bound by TenantScopeInterceptor around resource methods
- Accessible to all downstream code via scoped value

**Usage**: Audit logging, consent evaluation, admin commands track actor + request metadata

---

## 4. MULTI-TENANT ISOLATION

### 4.1 Database Schema Isolation

**TenantAwareExecuteListener** (operio-jooq):
- Location: `common/operio-jooq/src/main/java/com/koostack/operio/plugin/jooq/common/TenantAwareExecuteListener.java`
- **Hook**: JOOQ `ExecuteListener.end()` (after query binding)
- **Isolation**: `SET search_path TO t_{tenantId}, public`
- **Effect**: All queries automatically scoped to tenant schema

### 4.2 Configuration
- **Location**: `operio-rest-api/src/main/resources/application.yml`
- **Multi-Tenant Guard** (lines 100-112):
  ```yaml
  operio:
    multi-tenant:
      enabled: ${OPERIO_MULTI_TENANT_ENABLED:false}
  ```
- **Behavior when enabled**:
  - DSAR endpoints require active `TenantContext`
  - Returns 403 Forbidden if tenantId is null
  - All DB queries routed to correct schema

### 4.3 Virtual Threads Requirement
- **Why**: `ScopedValue` (used for TenantContext) requires structured thread semantics
- **Config**: `quarkus.virtual-threads.enabled: true`
- **Framework**: Quarkus (not Spring Boot)

---

## 5. ENDPOINT AUTHENTICATION & AUTHORIZATION PATTERNS

### 5.1 Platform IAM Resource (`/api/v1/platform/iam`)
- **Most endpoints**: Require `super-admin` role
- **Open endpoints**:
  - `POST /users` — Self-service registration (public)
  - `POST /login` — Authenticate with email/password (public)
  - `POST /refresh` — Refresh access token (public)
  - `POST /logout` — Logout (public)

### 5.2 Consent Resource (`/api/v1/subjects`)
- **Decorator**: `@TenantScoped`
- **Read endpoints** (e.g., evaluate, subject summary):
  - Require `consent:read` role
  - Or `tenant-read` role (implies consent:read)
- **Write endpoints** (grant, revoke, batch):
  - Require `consent:write` role
  - Or `tenant-write` role (implies consent:write)
- **Multi-tenant guard**: If enabled, require TenantContext

### 5.3 Platform Resource (`/api/v1/platform`)
- **All endpoints**: Require `super-admin` role
- **Operations**:
  - Tenant lifecycle (create, list, delete)
  - API key management
  - Billing management

### 5.4 Catalog/Admin Resources
- **Read endpoints**: Require `admin:read`
- **Write endpoints**: Require `admin:write`
- **Tenant-scoped**: Yes

---

## 6. TOKEN CREATION & EXPIRATION

### 6.1 Personal Access Tokens

**Creation** (PlatformIamResource):
```
POST /api/v1/platform/iam/me/tokens
{
  "name": "My API Key",
  "expiresAt": "2025-12-31T23:59:59Z"
}
```

**Storage** (platform_iam.access_token):
- Plaintext returned once
- SHA-256 hash stored in DB
- Roles snapshotted at creation (comma-separated)
- TenantId bound at creation
- CRC32 checksum for fast validation

**Lifecycle**:
- Status: "active", "revoked", etc.
- Expiration checked on every use
- Can be revoked via `DELETE /api/v1/platform/iam/me/tokens/{prefix}`

### 6.2 Keycloak Access Tokens
- **Issued by**: Keycloak token endpoint
- **Expiration**: Configured per realm (default 15 min)
- **Refresh**: Via refresh token grant
- **Claims**:
  - `sub` — user ID
  - `email` — user email
  - `realm_access.roles` — realm-level roles
  - `iss` — issuer (realm URL)
  - Custom claims per tenant/org

---

## 7. ROLE ASSIGNMENT FOR PERSONAL VS ORG CONTEXT

### 7.1 Personal Tenant Roles
- **Assigned at**: User registration (via RegisterUserOrchestrator)
- **Default**: User is `tenant-admin` of their own personal org
- **Immutable**: Personal org owner has full control

### 7.2 Organization Member Roles
- **Assigned via**: Platform IAM endpoints or org management
- **Methods**: 
  - Direct role assignment (admin API)
  - Invite flow (sends email with join link)
- **Roles**: `tenant-admin`, `tenant-write`, `tenant-read` per org
- **Verification**: Checked in request context, inherited in JWT

### 7.3 Platform Roles
- **Assignment**: Manual via `PUT /api/v1/platform/iam/users/{username}/roles`
- **Roles**: `super-admin` (only platform-level role)
- **Scope**: Applies to all tenants

---

## 8. SECURITY FILTERS & INTERCEPTORS

### 8.1 Execution Order
```
1. JAX-RS Filters (ContainerRequestFilter, priority=50)
   → ApiKeyAuthFilter (AUTHENTICATION priority)
      ├─ Extract bearer token
      ├─ Validate token (bootstrap/config/access/JWT)
      ├─ Build SecurityContext
      └─ Store IdentityContext in request property

2. CDI Interceptors (@AroundInvoke)
   → TenantScopeInterceptor (PLATFORM_BEFORE + 10)
      ├─ Read IdentityContext from request
      └─ Bind TenantContext + RequestContext ScopedValues

3. Resource Method Invocation
   → ConsentResource.grantConsent()
      ├─ Call Roles.requireRole(sc, CONSENT_WRITE)
      ├─ TenantContext.get() available
      ├─ RequestContext.actor() available
      └─ Dispatch command/query with context
```

### 8.2 Exception Mapping
- **OperioAuthException** → HTTP 401 Unauthorized (invalid token) or 403 Forbidden (insufficient role)
- **Mapped by**: `OperioExceptionMapper` (JAX-RS ExceptionMapper)

---

## 9. KEYCLOAK INTEGRATION COMPONENTS

### 9.1 IdpProvider SPI
- Location: `operio-core/src/main/java/com/koostack/operio/core/platform/iam/IdpProvider.java`
- Methods:
  - `AuthResult authenticate(tenantId, email, password)` — ROPC flow
  - `AuthResult refreshToken(tenantId, refreshToken)` — Refresh grant
  - `void logout(tenantId, refreshToken)` — Token revocation
  - `IdpUser registerUser(tenantId, email, password, fullName)` — User creation
  - `void verifyEmail(tenantId, userId, token)` — Email verification
  - `IdentityContext verifyToken(tenantId, token)` — JWT verification
  - `void provisionTenant(tenantId, displayName)` — **Deprecated** (per-realm)
  - `void deprovisionTenant(tenantId)` — **Deprecated** (per-realm)

### 9.2 KeycloakIdpProvider
- Location: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/KeycloakIdpProvider.java`
- Delegates to `KeycloakAuthSupport` for OIDC operations
- Uses `JwksTokenVerifier` for JWT verification

### 9.3 IdpTenantResolver
- Location: `operio-core/src/main/java/com/koostack/operio/core/platform/iam/IdpTenantResolver.java`
- Purpose: Route JWT verification to correct IdP per tenant
- Extensible: Allows per-tenant IdP overrides (Auth0, Okta, Azure AD, etc.)

---

## 10. CONFIGURATION

### 10.1 Keycloak Settings
- **Server URL**: `keycloak.server-url` (default: http://localhost:8180)
- **Admin Realm**: `keycloak.admin-realm` (default: master)
- **Admin Client**: `keycloak.admin-client-id`, `keycloak.admin-client-secret`
- **Platform Realm**: `keycloak.operio-realm-name` (default: operio)
- **Client ID**: `keycloak.operio-client-id` (default: operio-backend)
- **Client Secret**: `keycloak.operio-client-secret` (optional, dynamic lookup if missing)

### 10.2 Bootstrap Security
- **Bootstrap Key**: `operio.super-admin.bootstrap-key`
- **Force Bootstrap**: `operio.super-admin.force-bootstrap` (for disaster recovery)

### 10.3 API Keys
- **Config Format**:
  ```yaml
  operio:
    api-keys:
      "my-api-key": "role:consent:read,role:consent:write,tenant:my-tenant"
      "another-key": "role:super-admin"
  ```

---

## 11. AUTHENTICATION FLOW DIAGRAMS

### 11.1 JWT Authentication (Tenant User)
```
Client
  │
  └─→ Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
      │
      ├─→ ApiKeyAuthFilter
      │   ├─ Detect JWT (3 dots)
      │   ├─ Extract tenantId from payload
      │   └─ Call IdpProvider.verifyToken(tenantId, token)
      │       │
      │       └─→ JwksTokenVerifier.verify(jwksUrl, token)
      │           ├─ Fetch JWKS from /realms/t_{tenantId}/protocol/openid-connect/certs
      │           ├─ Verify RS256 signature
      │           ├─ Extract realm_access.roles
      │           └─ Return IdentityContext(subject, email, roles, tenantId)
      │
      ├─→ SecurityContext populated with roles
      └─→ IdentityContext stored in request property
          │
          └─→ TenantScopeInterceptor
              ├─ Bind TenantContext(tenantId)
              ├─ Bind RequestContext(actor, ip, ua)
              └─ Proceed to resource method
```

### 11.2 Personal Access Token Authentication
```
Client
  │
  └─→ Authorization: Bearer cveil_a8Kx3mNp...
      │
      ├─→ ApiKeyAuthFilter
      │   ├─ Detect cveil_ prefix
      │   ├─ Validate CRC32 checksum
      │   ├─ Compute SHA-256 hash
      │   └─ Query platform_iam.access_token
      │       ├─ Validate status = "active"
      │       ├─ Validate expiration
      │       ├─ Parse roles (comma-separated)
      │       └─ Return IdentityContext(subject, roles, tenantId)
      │
      └─→ Same as above (TenantScopeInterceptor)
```

### 11.3 User Registration (Personal Org Creation)
```
Client
  │
  └─→ POST /api/v1/platform/iam/users
      {
        "username": "john.doe",
        "email": "john@example.com",
        "fullName": "John Doe",
        "password": "..."
      }
      │
      ├─→ PlatformIamResource.registerUser()
      │   ├─ Derive tenantId from username → "john_doe"
      │   └─ Invoke RegisterUserOrchestrator
      │       │
      │       ├─ Step 1: RegisterUserStep
      │       │   └─ KeycloakIdpProvider.registerUser() → create in "operio" realm
      │       │
      │       ├─ Step 2: CreateTenantStep
      │       │   └─ Create tenant aggregate
      │       │
      │       ├─ Step 3: ProvisionRedisStep
      │       │   └─ Create Redis streams + ACL
      │       │
      │       ├─ Step 4: CreateBillingProfileStep
      │       │   └─ Create billing record
      │       │
      │       └─ Step 5: CreateSubscriptionStep
      │           └─ Create free-plan subscription
      │
      └─→ Return UserRegisteredResponse
          {
            "userId": "...",
            "username": "john.doe",
            "email": "john@example.com",
            "tenantId": "john_doe",
            "status": "unverified"
          }
```

---

## 12. FUTURE ROADMAP: Per-Tenant Realms → Single Realm + Organizations

### Current State (Phase 1)
- One realm per tenant: `t_<tenantId>`
- Tenant identification: Extract from `/realms/t_tenantId` in token issuer
- Client secrets: Cached per realm
- Realm lifecycle: Create/delete per tenant

### Target State (Phase 2)
- Single `operio` realm for all tenants
- Per-tenant isolation via **Keycloak Organizations**
- Tenant identification: Extract from `organization_id` JWT claim
- Organization lifecycle: Create/delete organizations, assign members
- Token issuer: Single `/realms/operio`

### Implementation Checklist
- [ ] Phase 1: Abstract realm resolution (✅ `RealmResolver` interface)
- [ ] Phase 2: Single token URL + secret caching per org
- [ ] Phase 3: Organization provisioning (create/delete orgs)
- [ ] Phase 4: JWT org claim extraction + token mapper
- [ ] Phase 5: Org-based query services (list users in org)
- [ ] Phase 6: Org-based deprovisioning
- [ ] Feature flag: `operio.keycloak.use-organizations=true`
- [ ] Gradual migration: New tenants use orgs, legacy use realms
- [ ] Post-migration cleanup: Remove realm-based code

**Breaking Changes**:
- Token issuer changes from `http://host/realms/t_tenantId` to `http://host/realms/operio`
- JWKS endpoint changes
- Tenant extraction from `organization_id` claim instead of issuer path

---

## 13. SECURITY BEST PRACTICES IMPLEMENTED

✅ **Token Validation**:
- Signature verification (RS256)
- Expiration checks
- Issuer validation (implicit in JWKS endpoint)
- CRC32 + SHA-256 checksums for access tokens

✅ **Tenant Isolation**:
- ScopedValue-based context (no ThreadLocal leaks)
- Database schema isolation (`SET search_path`)
- Virtual threads required (structured concurrency)

✅ **Role-Based Access Control**:
- Declarative role checks (`Roles.requireRole()`)
- Role hierarchy enforcement
- Super-admin override
- No hardcoded permissions

✅ **Secret Management**:
- 3-tier client secret resolution (cache → config → dynamic)
- No plaintext personal tokens returned (one-time)
- SHA-256 hashing for token storage

✅ **Audit Logging**:
- RequestContext carries actor, IP, user agent
- All auth decisions logged (debug level)

❌ **Not Yet Implemented**:
- Shared Keycloak Organizations (planned for Phase 2)
- Multi-IdP per-tenant routing (IdpTenantResolver ready, not used yet)
- Rate limiting per token (framework ready, not integrated)

---

## 14. KEY FILES REFERENCE

| File | Purpose |
|------|---------|
| `ApiKeyAuthFilter.java` | Primary auth entry point (4 mechanisms) |
| `TenantScopeInterceptor.java` | Binds TenantContext + RequestContext |
| `Roles.java` | RBAC hierarchy + enforcement |
| `IdentityContext.java` | Auth principal model |
| `TenantContext.java` | Multi-tenant scoping (ScopedValue) |
| `RequestContext.java` | Request metadata scoping |
| `KeycloakAuthSupport.java` | OIDC operations (ROPC, refresh, logout) |
| `JwksTokenVerifier.java` | JWT signature verification + role extraction |
| `IdpProvider.java` | Authentication SPI interface |
| `AuthzProvider.java` | Authorization SPI interface |
| `KeycloakAuthzProvider.java` | Role management (deprecated, use IAM services) |
| `RegisterUserOrchestrator.java` | Personal tenant creation flow |
| `TenantIdDeriver.java` | Username → sanitized tenant ID |
| `OperioRequestHolder.java` | Request-scoped identity + metadata holder |
| `PlatformIamResource.java` | User/org/token management endpoints |
| `ConsentResource.java` | Consent operations with RBAC |
| `PlatformResource.java` | Admin endpoints (super-admin only) |

---

## Summary

Operio's authentication and authorization system is **comprehensive and multi-layered**:

1. **Authentication** supports 4 methods: bootstrap key, config API keys, personal access tokens, JWT
2. **Authorization** enforces role hierarchy with super-admin override
3. **Multi-tenancy** uses Java 21 ScopedValues for context isolation + DB schema routing
4. **Personal vs org context** differentiated via tenantId in token or API key config
5. **Keycloak integration** provides flexible IdP with per-tenant realms (transitioning to single realm + orgs)
6. **Request context** (actor, IP, UA) automatically tracked for audit
7. **Future roadmap** plans Keycloak Organizations for improved architecture

The system is **production-ready** for single-tenant and multi-tenant deployments with strong security isolation.
