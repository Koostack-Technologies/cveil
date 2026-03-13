# Operio Keycloak Architecture

## Executive Summary

Operio uses **per-tenant Keycloak realms** to isolate authentication and authorization data by tenant. Each tenant gets its own realm named `t_<tenantId>`, containing users, roles, and service accounts. This document comprehensively maps the architecture, components, and realm lifecycle across the codebase.

**Version**: Keycloak Admin Client **26.0.8** (with Nimbus JOSE for JWT verification)

---

## 1. KeycloakAuthSupport (Common Layer)

**File**: `common/operio-keycloak/src/main/java/com/koostack/operio/common/keycloak/KeycloakAuthSupport.java`

**Purpose**: Shared OIDC helper for realm-agnostic authentication operations (token exchange, refresh, logout, verification).

### Key Responsibilities
- **ROPC (Resource Owner Password Credentials) grant** — authenticate via email/password
- **Refresh-token grant** — get new access tokens from refresh tokens
- **Logout (token revocation)** — revoke refresh tokens
- **Client secret resolution** — 3-tier strategy:
  1. Check in-memory cache (`clientSecretCache`)
  2. Fall back to static config value (`config.operioClientSecret()`)
  3. Dynamically resolve via Admin API (`adminClient.realm(realmId).clients()`)
- **JWT verification** — delegates to `JwksTokenVerifier` via realm's JWKS endpoint

### Constructor Variants
```java
// With explicit RealmResolver strategy
public KeycloakAuthSupport(
    KeycloakConfig config, 
    Keycloak adminClient, 
    RealmResolver realmResolver)

// Default: identity() resolver (input IS the realm ID)
public KeycloakAuthSupport(KeycloakConfig config, Keycloak adminClient)
```

### RealmResolver Strategy Pattern
The `RealmResolver` interface decouples realm naming from OIDC logic:
- **Platform callers** → `RealmResolver.identity()` — realm ID = input ID
- **Tenant callers** → `RealmResolver.tenantPrefix()` — realm ID = `t_<tenantId>`

### Key Methods
```java
public AuthResult authenticate(String id, String email, String password)
  // id → passes through RealmResolver to get realmId
  // Uses ROPC grant with resolved client secret

public AuthResult refreshToken(String id, String refreshToken)
  // Refreshes access token; caches secrets per realm

public void logout(String id, String refreshToken)
  // Revokes token via /realms/{realmId}/protocol/openid-connect/logout

private String resolveClientSecret(String realmId)
  // Cache → Config → Dynamic Admin API lookup
  // Throws OperioAuthException if no secret available

private AuthResult parseTokenResponse(String json, String realmId)
  // Extracts access_token, refresh_token, expires_in
  // Verifies via JwksTokenVerifier using realm's JWKS endpoint
```

---

## 2. RealmResolver Interface

**File**: `common/operio-keycloak/src/main/java/com/koostack/operio/common/keycloak/RealmResolver.java`

**Purpose**: Strategy for mapping caller-supplied identifiers (realm ID or tenant ID) to Keycloak realm names.

```java
@FunctionalInterface
public interface RealmResolver {
    String toRealmId(String id);

    // Platform variant: input IS the realm ID
    static RealmResolver identity() {
        return id -> id;
    }

    // Tenant variant: maps tenantId → t_<tenantId>
    static RealmResolver tenantPrefix() {
        return id -> "t_" + id;
    }
}
```

**Usage**:
- Platform: `KeycloakAuthSupport(config, adminClient)` → uses `identity()`
- Tenant: `KeycloakAuthSupport(config, adminClient, RealmResolver.tenantPrefix())` → uses `tenantPrefix()`

---

## 3. KeycloakConfig Record

**File**: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/KeycloakConfig.java`

**Purpose**: Configuration model for Keycloak IdP provider.

```java
public record KeycloakConfig(
    String serverUrl,              // Keycloak base URL (e.g., http://localhost:8180)
    String adminRealm,             // Admin realm (typically "master")
    String adminClientId,          // Admin client ID (e.g., "admin-cli")
    String adminUsername,          // Admin username
    String adminPassword,          // Admin password
    String operioClientId,         // Client ID in each tenant realm (e.g., "operio-backend")
    String operioClientSecret      // Client secret (auto-generated if null)
)
```

### Static Factory
```java
public static KeycloakConfig of(String serverUrl, String adminUsername, String adminPassword) {
    return new KeycloakConfig(
        serverUrl,
        "master",           // adminRealm
        "admin-cli",        // adminClientId
        adminUsername,
        adminPassword,
        "operio-backend",   // operioClientId
        null                // operioClientSecret (auto-generated)
    );
}
```

---

## 4. KeycloakRealmProvisioner

**File**: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/KeycloakRealmProvisioner.java`

**Purpose**: Provisions and deprovisions Keycloak realms for Operio tenants.

### Per-Tenant Realm Configuration
Each realm gets:
- **Name**: `t_<tenantId>` or custom display name
- **Confidential client**: `operio-backend` (ROPC + service account enabled)
- **Default roles**:
  - `consent:read`, `consent:write`
  - `admin:read`, `admin:write`
  - `tenant-admin`, `tenant-write`, `tenant-read`
- **Token lifetimes**:
  - Access: 15 minutes (900 seconds)
  - Refresh: 7 days (604800 seconds)
- **Security**:
  - Email verification required
  - Brute-force protection (5 failures in 900 seconds)

### Key Methods
```java
public ProvisionResult provisionRealm(String realmId)
  // Creates realm with realmId as both name and display name
  // Returns: ProvisionResult(realmId, clientSecret)

public String provisionRealm(String tenantId, String displayName)
  // Creates realm with custom display name
  // Calls: createOperioClient() → createDefaultRoles()

public void deprovisionRealm(String tenantId)
  // Deletes realm; silently ignores NotFoundException

public void createRootUser(String realmId, String email)
  // Creates user with tenant-admin role
  // Triggers UPDATE_PASSWORD required action for password reset

public ProvisionResult ensurePlatformRealm(
    String platformRealmId, String rootEmail, String rootPassword)
  // Ensures platform realm exists (idempotent)
  // Creates super-admin role if missing
  // Creates or updates root user with full platform access

private String createOperioClient(RealmResource realmResource, String tenantId)
  // Creates confidential client with ROPC + service accounts enabled
  // Returns generated client secret

private void createDefaultRoles(RealmResource realmResource)
  // Creates all DEFAULT_ROLES
```

### Result Record
```java
public record ProvisionResult(String realmId, String clientSecret) {}
```

---

## 5. KeycloakIdpProvider (Platform)

**File**: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/KeycloakIdpProvider.java` (552 lines)

**Purpose**: Platform-level IdP provider implementation. Handles user management, realm provisioning, authentication, and JWT verification.

### Key Lifecycle Methods
```java
public void configure(KeycloakConfig config)
  // Builds Keycloak admin client via KeycloakBuilder
  // Initializes: realmProvisioner, jwksVerifier, httpClient

public boolean isReady()
  // Attempts adminClient.realm(config.adminRealm()).toRepresentation()
  // Returns true if admin realm is accessible
```

### Authentication Methods (delegated to HTTP)
```java
public AuthResult authenticate(String tenantId, String email, String password)
  // ROPC: POST to /realms/{tenantId}/protocol/openid-connect/token
  // Resolves client secret; parses access_token, refresh_token, expires_in

public AuthResult refreshToken(String tenantId, String refreshToken)
  // grant_type=refresh_token; similar flow

public void logout(String tenantId, String refreshToken)
  // POST to /realms/{tenantId}/protocol/openid-connect/logout
```

### User Management Methods
```java
public IdpUser registerUser(String tenantId, String email, String password, String fullName)
  // Creates user via adminClient.realm(tenantId).users().create()
  // Sets email, username (=email), first/last name, required action (VERIFY_EMAIL)
  // Sends verification email

public void verifyEmail(String tenantId, String userId, String verificationToken)
  // Sets emailVerified=true on user

public void resetPassword(String tenantId, String userId, String newPassword)
  // Resets password via adminClient.realm(tenantId).users().get(userId).resetPassword()

public void suspendUser(String tenantId, String userId)
  // Sets enabled=false

public void unsuspendUser(String tenantId, String userId)
  // Sets enabled=true
```

### Secret Resolution
```java
private String resolveClientSecret(String tenantId)
  // Cache → Config value → Admin API lookup
  // Caches per-tenant secrets in ConcurrentHashMap
```

---

## 6. JwksTokenVerifier

**File**: `platform/operio-platform-keycloak/src/main/java/com/koostack/operio/plugin/platform/keycloak/JwksTokenVerifier.java` (100+ lines)

**Purpose**: Generic JWKS-based JWT verifier (works with any OIDC IdP).

### Key Features
- **Caching**: Per-issuer JWKS key sets with 5-minute TTL
- **Key rotation handling**: Auto-refresh JWKS on `kid` miss
- **Signature verification**: RS256 algorithm via Nimbus JOSE

### Key Methods
```java
public IdentityContext verify(String jwksUrl, String token, String tenantId)
  // Fetches JWKS from jwksUrl
  // Verifies RS256 signature
  // Returns: IdentityContext with identity claims

private JWTClaimsSet verifyAndParseClaims(String jwksUrl, String token)
  // Calls processToken() with cached JWKS
  // On BadJOSEException, refreshes JWKS and retries once
```

---

## 7. KeycloakConfig (All Files in platform/operio-platform-keycloak/)

| File | Purpose |
|------|---------|
| `KeycloakConfig.java` | Configuration record |
| `KeycloakIdpProvider.java` | Platform IdP provider (user mgmt, auth, verification) |
| `KeycloakRealmProvisioner.java` | Realm creation/deletion, root user setup |
| `JwksTokenVerifier.java` | JWKS-based JWT verification |

---

## 8. ApiKeyAuthFilter (operio-rest-api)

**File**: `operio-rest-api/src/main/java/com/koostack/operio/rest/filter/ApiKeyAuthFilter.java` (600+ lines)

**Purpose**: JAX-RS authentication filter (runs at `Priorities.AUTHENTICATION`). First-line defense; authenticates every incoming request.

### Authentication Flow (in order)
1. **Skip public paths**: Health checks, Kubernetes probes
2. **Skip CORS preflight**: OPTIONS requests
3. **Extract Bearer token** from `Authorization: Bearer <token>` header
4. **Check bootstrap key** (lifecycle-aware via `BootstrapKeyLifecycleService`):
   - If active → grant `super-admin` role, no tenant scope
   - If consumed → abort with 403
5. **Check config API keys** from `operio.api-keys.*` (dev/test convenience):
   - Format: `token → "role:X,role:Y,tenant:Z"`
6. **Detect JWT** (3 dot-separated base64url segments):
   - Extract tenant ID from JWT payload (without verification)
   - Route to tenant-specific `IdpProvider.verifyToken()`
7. **Verify via TokenValidationService** (API key hash lookup in platform DB):
   - SHA-256 hashes token; looks up in `api_key` table
8. **On success**: Set JAX-RS `SecurityContext`, store `IdentityContext` in request property
9. **On failure**: Abort with HTTP 401 JSON error

### Key Methods
```java
@Override
public void filter(ContainerRequestContext ctx) throws IOException
  // Full authentication chain (7 checks above)

private IdentityContext resolveConfigApiKey(String token)
  // Parses "role:X,tenant:Y" format from operio.api-keys map

private static boolean isJwt(String token)
  // Checks for exactly 2 dots (header.payload.signature)

private static String extractTenantFromJwt(String token)
  // Extracts tenant_id, tenantId, or issuer-derived realm name
  // Keycloak issuer format: http://host/realms/{realmName}

private void verifyJwt(ContainerRequestContext ctx, String token)
  // Resolves IdpProvider for tenant
  // Delegates to idpProvider.verifyToken()

private static String extractJsonStringValue(String json, String key)
  // Minimal JSON extraction (avoids parser in hot path)
```

### Request Properties
- **`IDENTITY_CONTEXT_PROPERTY`** = `"operio.identity-context"` — stores authenticated `IdentityContext`
- Read by `TenantScopeInterceptor` to bind `TenantContext` and `RequestContext` scoped values

---

## 9. All Keycloak-Related Classes in operio-keycloak (Common)

| File | Purpose |
|------|---------|
| `KeycloakAuthSupport.java` | Shared OIDC operations (auth, refresh, logout, secret resolution, JWT verification) |
| `RealmResolver.java` | Strategy interface for tenantId → realmId mapping |

**Dependency**: 
```gradle
api("org.keycloak:keycloak-admin-client:26.0.8")
```

---

## 10. Tenant-Side Keycloak Integration (operio-tenant-iam-query-kc, etc.)

### Files
| Path | Purpose |
|------|---------|
| `operio-tenant-iam-query-kc/src/main/java/com/koostack/operio/tenant/iam/kc/KeycloakTenantIamQueryService.java` | Read users, roles, service accounts from tenant realm |
| `operio-tenant-iam-query-kc/src/main/java/com/koostack/operio/tenant/iam/kc/TenantKeycloakUserMapper.java` | Convert Keycloak representations to domain models |
| `operio-tenant-iam-command/src/main/java/com/koostack/operio/tenant/iam/kc/KeycloakTenantIamCommandService.java` | Publish domain events for user/service-account operations |
| `operio-tenant-iam-query-kc/src/main/java/com/koostack/operio/tenant/iam/kc/KeycloakTenantIamQueryDispatcher.java` | CQRS dispatcher |
| `operio-authz-keycloak/src/main/java/com/koostack/operio/tenant/authz/keycloak/KeycloakAuthzProvider.java` | Authorization provider (role/permission checks) |

### Realm ID Transformation
```java
// TenantKeycloakUserMapper.java
public static String toRealmId(String tenantId) {
    return "t_" + tenantId;
}
```

All tenant operations use `adminClient.realm(toRealmId(tenantId))` to access the tenant-specific realm.

### Example: List Users
```java
// KeycloakTenantIamQueryService.java
@Override
public List<IdpUser> listUsers(String tenantId) {
    String realmId = toRealmId(tenantId);  // "t_<tenantId>"
    return adminClient.realm(realmId).users().list()
        .stream()
        .map(this::toIdpUser)
        .toList();
}
```

---

## 11. All References to `createRealm`, `realmResource`, `realm()` Calls

### Realm Creation (Provisioning)
```java
// KeycloakRealmProvisioner.java (line 96)
adminClient.realms().create(realm);  // Create realm via Admin API

// KeycloakRealmProvisioner.java (line 99)
RealmResource realmResource = adminClient.realm(tenantId);
```

### Realm Access (Query/Command Operations)
- **Tenant-side**: All files using `adminClient.realm(realmId)` with `realmId = "t_" + tenantId`:
  - `KeycloakTenantIamQueryService.java` — 20+ calls
  - Query actuators (UserList, UserGet, VerifyToken, etc.)
  - `KeycloakAuthzProvider.java` — role/permission checks

- **Platform-side**: All files using `adminClient.realm(tenantId)` or `adminClient.realm(config.adminRealm())`:
  - `KeycloakIdpProvider.java` — user registration, email verification, password reset
  - `KeycloakRealmProvisioner.java` — root user creation, platform realm setup

### Total Occurrences (non-test)
- **~50+ direct calls** to `.realm(realmId)` across the codebase
- **~2 calls** to `adminClient.realms().create()` (realm provisioning)
- **~2 calls** to `adminClient.realm(id).remove()` (realm deprovisioning)

---

## 12. Keycloak Java Admin Client SDK Version

**Version**: **26.0.8**

**Where Declared**:
- `common/operio-keycloak/build.gradle.kts`:
  ```gradle
  val keycloakVersion = "26.0.8"
  api("org.keycloak:keycloak-admin-client:$keycloakVersion")
  ```
- Also declared in: `tenant/`, `platform/operio-platform-keycloak/`, and other modules

**Additional Dependencies** (pulled transitively):
- **Nimbus JOSE**: JWT/JOSE processing (used by `JwksTokenVerifier`)
- **Jackson**: JSON parsing

---

## 13. Current Per-Tenant Realm Architecture

### Realm Isolation Model
```
Keycloak Instance
├── admin realm (master)
│   └── admin-cli client (for API access)
├── t_tenant-1
│   ├── operio-backend client (ROPC, service accounts)
│   ├── Users (stored in this realm only)
│   ├── Roles: consent:*, admin:*, tenant-*
│   └── Service accounts
├── t_tenant-2
│   ├── operio-backend client
│   ├── Users
│   ├── Roles
│   └── Service accounts
└── operio-platform (single platform realm)
    ├── operio-backend client
    ├── Platform users
    ├── Roles: super-admin, admin, user
    └── Service accounts
```

### Tenant Data Isolation
- **Realm separation**: Each tenant's users/roles exist only in their `t_<tenantId>` realm
- **Token scoping**: Access tokens issued by tenant realm include `iss: http://keycloak/realms/t_<tenantId>`
- **Client secret per realm**: Different `operio-backend` client in each realm with unique secret
- **No cross-realm access**: Tenant users cannot authenticate to other realms

---

## 14. Refactoring Path: Per-Tenant Realms → Single Realm + Organizations

### Key Architectural Changes Required

**Current State**:
- 1 realm per tenant (t_tenant-1, t_tenant-2, ..., t_tenant-N)
- Realm ID = tenant identifier
- Client secrets cached per realm

**Target State** (with Keycloak Organizations):
- 1 realm for all tenants
- 1 organization per tenant (org = tenant)
- Tenant scoping via organization membership, not realm boundaries
- Token claims: `organization_id` or `org_id` (custom mapper)

### Refactoring Checklist

#### Phase 1: Abstract Realm Resolution
- ✅ Already done: `RealmResolver` interface decouples realm naming
- Extend: Create `TenantMapping` interface for `tenantId → org_id` resolution
- Update: `KeycloakAuthSupport` to use new mapping

#### Phase 2: Client Secrets & Token URLs
- **Current**: `tokenUrl = /realms/{realmId}/protocol/openid-connect/token`
- **New**: Single token URL: `/realms/{singleRealmName}/protocol/openid-connect/token`
- Update: `KeycloakIdpProvider.authenticate()`, `KeycloakAuthSupport.authenticate()`
- Cache: Secrets keyed by organization, not realm

#### Phase 3: User/Role Provisioning
- **Current**: Each tenant realm has separate user set + roles
- **New**: Users in single realm; role assignments via organization context
- Create organization resource via Admin API: `adminClient.realm(realmName).organizations().create()`
- Assign users to org: `org.members().addMember(userId)`
- Org-level roles: Map tenant roles to org-level role scopes

#### Phase 4: JWT & Token Verification
- **Current**: `extractTenantFromJwt()` pulls from issuer path
- **New**: Extract from `organization_id` claim or custom mapper output
- Update: `JwksTokenVerifier` to extract org_id instead of realm
- Mapper: Add Keycloak User Attribute Mapper or Script Mapper:
  ```
  User attribute: "org_id" → JWT claim: "organization_id"
  ```

#### Phase 5: Query Services (Tenant-side)
- **Current**: `adminClient.realm("t_" + tenantId).users().list()`
- **New**: Query users filtered by organization:
  ```
  adminClient.realm(singleRealmName).organizations()
      .org(tenantId).members().getAll()
  ```
- **Alternative**: Use User Search API with filters:
  ```
  adminClient.realm(singleRealmName).users()
      .search(..., "attribute:org_id:" + tenantId)
  ```

#### Phase 6: Deprovisioning
- **Current**: `adminClient.realm(tenantId).remove()`
- **New**: `adminClient.realm(realmName).organizations().org(tenantId).delete()`
- Cleanup: Remove organization members, roles, resources

### Breaking Changes
1. **Token issuer** changes from `http://host/realms/t_tenantId` to `http://host/realms/single-realm`
   - Update `JwksTokenVerifier.verify()` jwksUrl construction
   - Update client validation to expect single issuer
   
2. **JWKS endpoint** changes:
   - From: `/realms/t_<tenantId>/protocol/openid-connect/certs`
   - To: `/realms/single-realm/protocol/openid-connect/certs`

3. **Tenant extraction** from token:
   - From: Parse `/realms/t_tenantId` from issuer
   - To: Read `organization_id` claim

4. **Database migrations** (for Operio event sourcing):
   - Realm column might no longer apply (single realm)
   - Org_id column becomes primary tenant reference

### Implementation Plan
1. Introduce `TenantOrganizationResolver` interface (parallel to `RealmResolver`)
2. Update `KeycloakAuthSupport`, `KeycloakIdpProvider` to support both realm and org modes
3. Create `KeycloakOrganizationProvisioner` for org lifecycle
4. Feature flag: `operio.keycloak.use-organizations=true` (backward compatible)
5. Dual-write migration: New tenants use orgs, legacy use realms
6. Gradual tenant migration: Re-assign users to org, then delete legacy realms
7. Post-migration cleanup: Remove realm-based code paths

---

## 15. Quick Reference: Key Classes & Interfaces

| Class/Interface | Module | Purpose |
|---|---|---|
| `KeycloakAuthSupport` | common/operio-keycloak | Shared OIDC operations |
| `RealmResolver` | common/operio-keycloak | TenantId → RealmId mapping strategy |
| `KeycloakConfig` | platform/operio-platform-keycloak | Configuration record |
| `KeycloakIdpProvider` | platform/operio-platform-keycloak | Platform IdP (user mgmt, auth) |
| `KeycloakRealmProvisioner` | platform/operio-platform-keycloak | Realm provisioning lifecycle |
| `JwksTokenVerifier` | platform/operio-platform-keycloak | JWKS-based JWT verification |
| `ApiKeyAuthFilter` | operio-rest-api | JAX-RS auth filter (bootstrap, API keys, JWT, DB tokens) |
| `KeycloakTenantIamQueryService` | operio-tenant-iam-query-kc | Read users/roles from tenant realm |
| `TenantKeycloakUserMapper` | operio-tenant-iam-query-kc | Keycloak → domain model mapping |
| `KeycloakTenantIamCommandService` | operio-tenant-iam-command | Publish tenant IAM domain events |
| `KeycloakAuthzProvider` | operio-authz-keycloak | Tenant authorization (roles, permissions) |

