# OPERIO AUTHORIZATION CHECKS CATALOG
## Complete Authorization & Permission Enforcement Points

---

## EXECUTIVE SUMMARY

This catalog documents **every authorization check and permission enforcement point** across the Operio codebase. Authorization is enforced through:

1. **REST API Layer:** Programmatic role-based access control (RBAC) via `Roles.requireRole()`
2. **Service Layer:** Consent-driven data access via `@ConsentRequired` and `@Sensitive` aspects
3. **JAX-RS SecurityContext:** API key authentication and role assignment
4. **JWT Authentication:** Human user authentication flows (login, tokens, profiles)

---

## 1. ROLE DEFINITIONS & HIERARCHY

**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/auth/Roles.java` (Lines 64-209)

### Defined Roles

| Role Constant | String Value | Purpose |
|---|---|---|
| CONSENT_READ | "consent:read" | Read-only consent evaluation |
| CONSENT_WRITE | "consent:write" | Mutating consent operations (grant/revoke) |
| ADMIN_READ | "admin:read" | Read-only admin operations (list/get) |
| ADMIN_WRITE | "admin:write" | Mutating admin operations (register/update/delete) |
| SUPER_ADMIN | "super-admin" | Platform-level admin, overrides all checks |
| TENANT_ADMIN | "tenant-admin" | Full IAM management for tenant |
| TENANT_WRITE | "tenant-write" | CRUD on catalogue, subject, governance, consent |
| TENANT_READ | "tenant-read" | Read-only access to tenant resources |

### Role Hierarchy Map (Lines 118-135)

```java
tenant-admin       → {consent:read, consent:write, admin:read, admin:write, tenant-write, tenant-read}
tenant-write       → {consent:read, consent:write, admin:read, admin:write, tenant-read}
tenant-read        → {consent:read, admin:read}
```

**Super-Admin Override:** `super-admin` implicitly bypasses all role checks via `Roles.requireRole()` logic (lines 168-170).

### Enforcement Methods

| Method | Signature | Behavior | File Location |
|---|---|---|---|
| `requireRole()` | `static void requireRole(SecurityContext sc, String role)` | Enforces single role requirement; throws OperioAuthException on denial | Lines 166-176 |
| `requireAnyRole()` | `static void requireAnyRole(SecurityContext sc, String... roles)` | Accepts any of multiple roles; throws OperioAuthException if none match | Lines 190-204 |
| `hasEffectiveRole()` | `private static boolean hasEffectiveRole(SecurityContext ctx, String role)` | Checks role directly or via hierarchy | Lines 138-149 |

---

## 2. REST API ENDPOINT AUTHORIZATION MAP

### A. CONSENT RESOURCE
**Path:** `/api/v1/subjects`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/ConsentResource.java`

#### Consent Mutation Operations
| HTTP Method | Path | Operation | Role Check | Line |
|---|---|---|---|---|
| POST | /consent/grant | Grant consent to subject | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 165 |
| POST | /consent/revoke | Revoke consent from subject | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 230 |
| POST | /consent/grant-all | Batch grant consent | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 296 |
| POST | /consent/revoke-all | Batch revoke consent | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 387 |

#### Consent Read Operations
| HTTP Method | Path | Operation | Role Check | Line |
|---|---|---|---|---|
| POST | /{subjectId}/evaluate | Evaluate consent for subject | `Roles.requireRole(securityContext, CONSENT_READ)` | 482 |
| GET | /{id}/summary | Get subject consent summary | `Roles.requireRole(securityContext, CONSENT_READ)` | 542 |
| GET | /{id}/history | Get subject consent history | `Roles.requireRole(securityContext, CONSENT_READ)` | 593 |

#### Subject Mutation Operations
| HTTP Method | Path | Operation | Role Check | Line |
|---|---|---|---|---|
| POST | / | Register subject | `Roles.requireRole(sc, ADMIN_WRITE)` | 960 |
| PUT | /{name} | Update subject | `Roles.requireRole(sc, ADMIN_WRITE)` | 989 |
| DELETE | /{name} | Delete subject | `Roles.requireRole(sc, ADMIN_WRITE)` | 1023 |
| DELETE | /{name}/dsar-key | Anonymize subject (DSAR) | `Roles.requireRole(sc, ADMIN_WRITE)` | 1058 |

#### Subject Read Operations
| HTTP Method | Path | Operation | Role Check | Line |
|---|---|---|---|---|
| GET | / | List subjects | `Roles.requireRole(sc, ADMIN_READ)` | 894 |
| GET | /{name} | Get subject by name | `Roles.requireRole(sc, ADMIN_READ)` | 926 |

#### Subject Data Export
| HTTP Method | Path | Operation | Role Check | Line |
|---|---|---|---|---|
| GET | /{id}/export | Export subject data | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 651 |
| DELETE | /{id}/audit | Delete subject audit/history | `Roles.requireRole(securityContext, CONSENT_WRITE)` | 760 |

---

### B. CATALOGUE RESOURCE
**Path:** `/api/v1/catalogue`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/CatalogueResource.java`

#### Fields
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /fields | List fields | ADMIN_READ | 99 |
| POST | /fields | Register field | ADMIN_WRITE | 119 |
| DELETE | /fields/{entityName}/{fieldName} | Remove field | ADMIN_WRITE | 148 |
| POST | /fields/categories | Assign field to category | ADMIN_WRITE | 169 |
| DELETE | /fields/{entityName}/{fieldName}/categories/{categoryName} | Unassign field from category | ADMIN_WRITE | 205 |

#### Entities
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /entities | List entities | ADMIN_READ | 231 |
| GET | /entities/{entityName} | Get entity | ADMIN_READ | 252 |
| POST | /entities | Register entity | ADMIN_WRITE | 279 |
| PUT | /entities/{entityName} | Update entity | ADMIN_WRITE | 305 |
| DELETE | /entities/{entityName} | Remove entity | ADMIN_WRITE | 328 |

#### Categories
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /categories | List categories | ADMIN_READ | 368 |
| GET | /categories/{categoryName} | Get category | ADMIN_READ | 390 |
| POST | /categories | Register category | ADMIN_WRITE | 418 |
| PUT | /categories/{categoryName} | Update category | ADMIN_WRITE | 444 |
| DELETE | /categories/{categoryName} | Remove category | ADMIN_WRITE | 468 |

---

### C. GOVERNANCE RESOURCE
**Path:** `/api/v1/governance`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/GovernanceResource.java`

#### Purposes
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /purposes | List purposes | ADMIN_READ | 104 |
| GET | /purposes/{purposeName} | Get purpose | ADMIN_READ | 125 |
| POST | /purposes | Register purpose | ADMIN_WRITE | 152 |
| DELETE | /purposes/{purposeName} | Remove purpose | ADMIN_WRITE | 176 |
| POST | /purposes/policies | Add purpose policy | ADMIN_WRITE | 197 |
| DELETE | /purposes/policies | Remove purpose policy | ADMIN_WRITE | 225 |
| POST | /purposes/regulations | Link purpose to regulation | ADMIN_WRITE | 251 |
| DELETE | /purposes/{purposeName}/regulations/{regulationName} | Unlink purpose from regulation | ADMIN_WRITE | 288 |

#### Regulations
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /regulations | List regulations | ADMIN_READ | 314 |
| GET | /regulations/{regulationName} | Get regulation | ADMIN_READ | 336 |
| POST | /regulations | Register regulation | ADMIN_WRITE | 365 |
| DELETE | /regulations/{regulationName} | Remove regulation | ADMIN_WRITE | 389 |
| POST | /regulations/jurisdictions | Add jurisdiction to regulation | ADMIN_WRITE | 410 |
| DELETE | /regulations/{regulationName}/jurisdictions/{jurisdictionName} | Remove jurisdiction | ADMIN_WRITE | 440 |

#### Jurisdictions
| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /jurisdictions | List jurisdictions | ADMIN_READ | 466 |
| GET | /jurisdictions/{jurisdictionName} | Get jurisdiction | ADMIN_READ | 488 |
| POST | /jurisdictions | Register jurisdiction | ADMIN_WRITE | 518 |
| PUT | /jurisdictions/{jurisdictionName} | Update jurisdiction | ADMIN_WRITE | 544 |
| DELETE | /jurisdictions/{jurisdictionName} | Remove jurisdiction | ADMIN_WRITE | 568 |

---

### D. ADMIN EVENTS RESOURCE
**Path:** `/api/v1/events`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/AdminEventsResource.java`

| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /invalidations | Stream admin cache invalidation events (SSE) | ADMIN_READ | 58 |

---

### E. GRAPH RESOURCE
**Path:** `/api/v1/consent`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/GraphResource.java`

| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /graph | Retrieve consent graph topology | CONSENT_READ | 89 |

---

### F. PLATFORM RESOURCE (Tenant Lifecycle)
**Path:** `/api/v1/platform`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/PlatformResource.java`

**All endpoints enforced via:** `requireSuperAdmin(securityContext)` → `Roles.requireRole(securityContext, SUPER_ADMIN)`

| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| POST | /tenants | Create tenant (DEPRECATED) | SUPER_ADMIN | 112 |
| GET | /tenants | List all tenants | SUPER_ADMIN | 156 |
| GET | /tenants/{id} | Get tenant details | SUPER_ADMIN | 210 |
| DELETE | /tenants/{id} | Delete tenant | SUPER_ADMIN | 267 |
| PUT | /tenants/{id}/plan | Update tenant subscription plan | SUPER_ADMIN | 335 |

---

### G. PLATFORM IAM RESOURCE (User & Organization Management)
**Path:** `/api/v1/platform/iam`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/PlatformIamResource.java`

#### User Management Endpoints

| HTTP Method | Path | Operation | Authorization | Line |
|---|---|---|---|---|
| POST | /users | Register user (self-service) | **NONE (PUBLIC)** — No auth required | 76 |
| GET | /users | List users | `requireSuperAdmin(sc)` → SUPER_ADMIN | 129 |
| GET | /users/{username} | Get user by username | `requireSuperAdmin(sc)` → SUPER_ADMIN | 146 |
| GET | /users/by-email | Find user by email | `requireSuperAdmin(sc)` → SUPER_ADMIN | 170 |
| PUT | /users/{username}/suspend | Suspend user | `requireSuperAdmin(sc)` → SUPER_ADMIN | 199 |
| PUT | /users/{username}/activate | Activate user | `requireSuperAdmin(sc)` → SUPER_ADMIN | 213 |
| GET | /users/{username}/roles | Get user roles | `requireSuperAdmin(sc)` → SUPER_ADMIN | 227 |
| PUT | /users/{username}/roles | Assign role to user | `requireSuperAdmin(sc)` → SUPER_ADMIN | 245 |
| DELETE | /users/{username}/roles/{role} | Remove role from user | `requireSuperAdmin(sc)` → SUPER_ADMIN | 263 |

#### Organization Management Endpoints (JWT Auth Required)

| HTTP Method | Path | Operation | Authorization | Line |
|---|---|---|---|---|
| POST | /organizations | Create organization | `requireJwtAuth()` | 288 |
| GET | /organizations | List organizations | No auth check | 332 |
| GET | /organizations/{orgId} | Get organization | `requireJwtAuth()` | 354 |
| GET | /organizations/{orgId}/members | List org members | `requireJwtAuth()` | 380 |
| POST | /organizations/{orgId}/members | Add org member | `requireJwtAuth()` | 402 |
| DELETE | /organizations/{orgId}/members/{userId} | Remove org member | `requireJwtAuth()` | 426 |
| POST | /organizations/{orgId}/members/invites | Invite org member | `requireJwtAuth()` | 453 |
| PUT | /organizations/{orgId} | Update organization | `requireJwtAuth()` | 498 |

#### Access Token Management (JWT Auth Required)

| HTTP Method | Path | Operation | Authorization | Line |
|---|---|---|---|---|
| POST | /me/tokens | Create access token | `requireJwtAuth()` | 546 |
| GET | /me/tokens | List access tokens | `requireJwtAuth()` | 614 |
| DELETE | /me/tokens/{tokenPrefix} | Revoke access token | `requireJwtAuth()` | 643 |

#### Authentication Endpoints (No Auth Required - Public)

| HTTP Method | Path | Operation | Authorization |
|---|---|---|---|
| POST | /login | User login | None (public) |
| POST | /refresh | Refresh JWT token | None (public) |
| POST | /logout | User logout | None (public) |

#### User Profile Management (JWT Auth Required)

| HTTP Method | Path | Operation | Authorization | Line |
|---|---|---|---|---|
| GET | /me | Get current user profile | `requireJwtAuth()` | 744 |
| PUT | /me | Update profile | `requireJwtAuth()` | 791 |
| PUT | /me/password | Change password | `requireJwtAuth()` | 824 |

**Note:** `requireJwtAuth()` custom helper (line 861) checks:
1. If caller has SUPER_ADMIN role → allow
2. Otherwise checks for JWT organization scope
3. Throws if neither condition met

---

### H. PLATFORM DOCS RESOURCE
**Path:** `/openapi.json`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/docs/PlatformDocsResource.java`

| HTTP Method | Path | Operation | Role Required | Line |
|---|---|---|---|---|
| GET | /openapi.json | Get OpenAPI specification | SUPER_ADMIN | 62 |

---

### I. HEALTH RESOURCE
**Path:** `/api/v1/health`  
**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/resource/HealthResource.java`

| HTTP Method | Path | Operation | Authorization |
|---|---|---|---|
| GET | / | Application health check | **NONE (PUBLIC)** — No auth required |

**Comment (Lines 26-28):** "This endpoint is public (no authentication required) and is designed for use by load balancers, Kubernetes probes, and monitoring systems."

---

## 3. SPRING BOOT STARTER - ASPECT-BASED AUTHORIZATION

### A. ConsentRequiredAspect
**File:** `operio-spring-boot-starter/src/main/java/com/koostack/operio/spring/aspect/ConsentRequiredAspect.java`

**Annotation:** `@ConsentRequired`

**Intercepts:** Methods annotated with `@ConsentRequired`

**Enforcement Logic (Lines 60-149):**

1. **Resolve Identity** (Line 86): `IdentityResolver.resolve()` → `IdentityContext`
2. **Build ConsentScope** (Lines 90-95): 
   - `dataType` → `category`
   - `fieldName` → `fieldName`
   - `entityName` → `entityName`
   - `purpose` → `purposeName`
3. **Evaluate Consent** (Line 100): `ConsentDecisionService.evaluateConsent(subject, scopes)`
4. **Decision:**
   - If denied (Line 104): Log denial, throw `ConsentDeniedException`, **block method**
   - If granted (Lines 132-148): Log grant, proceed with method execution
5. **Graceful Degradation** (Lines 67-83): If no resolver/service configured, logs warning and allows execution

**Audit Logging:**
- `AuditLogger.logAccessDenied(subject, scope, reason)` (Line 112)
- `AuditLogger.logAccessGranted(subject, scope)` (Line 133)

---

### B. SensitiveAspect
**File:** `operio-spring-boot-starter/src/main/java/com/koostack/operio/spring/aspect/SensitiveAspect.java`

**Annotation:** `@Sensitive`

**Intercepts:** Methods annotated with `@Sensitive`

**Enforcement Logic (Lines 64-164):**

1. **Resolve Identity** (Line 102): `IdentityResolver.resolve()` → `IdentityContext`
2. **Build ConsentScope** (Lines 106-110):
   - `category` → `category`
   - `fieldName` → `fieldName`
   - `purpose` → `purposeName`
3. **Evaluate Consent** (Line 115): `ConsentDecisionService.evaluateConsent(subject, scopes)`
4. **Proceed Method** (Line 118): Always executes the method regardless of consent
5. **Decision:**
   - If denied (Line 122): Log denial, apply `MaskingStrategy.mask(returnValue, deniedScopes)` (Line 145)
   - If granted (Lines 149-162): Log grant, return value unmodified
6. **Graceful Degradation** (Lines 77-99): If services not configured, logs warning and returns unmodified

**Audit Logging:**
- `AuditLogger.logAccessDenied(subject, scope, reason)` (Line 130)
- `AuditLogger.logAccessGranted(subject, scope)` (Line 150)

---

## 4. AUTHENTICATION & SECURITY CONTEXT

**File:** `operio-rest-api/src/main/java/com/koostack/operio/rest/filter/ApiKeyAuthFilter.java`

**Key Responsibilities:**
- Extracts API key from `Authorization: Bearer <api-key>` header
- Resolves API key to `IdentityContext` with assigned roles
- Populates JAX-RS `SecurityContext` with those roles
- Enables `SecurityContext.isUserInRole(roleName)` checks

**SecurityContext Usage:**
- `SecurityContext.isUserInRole(role)` — Used in:
  - `Roles.requireRole()` (Line 140)
  - `Roles.requireAnyRole()` (Line 197)
  - `Roles.hasEffectiveRole()` (Lines 140, 144)
  - `PlatformIamResource.requireJwtAuth()` (Lines 872, 874, 881)

**Annotation Support (Not Actively Used):**
- `@RolesAllowed` — Prepared but not used in production
- `@PermitAll` — Prepared but not used in production
- `@Secured` — Not mentioned
- All enforcement is **programmatic** via explicit `Roles.requireRole()` calls

---

## 5. AUTHORIZATION PATTERNS SUMMARY

### Pattern 1: Programmatic Role-Based Access Control (RBAC)
**Location:** REST API endpoints  
**Method:** `Roles.requireRole(securityContext, role)`  
**Implementation:**
```java
Roles.requireRole(securityContext, Roles.CONSENT_WRITE);
```
**Behavior:**
- Checks if caller has required role (or bypasses if SUPER_ADMIN)
- Throws `OperioAuthException` → HTTP 403 on denial
- Respects role hierarchy (tenant-admin → admin:write, etc.)

**Endpoints Using:** ConsentResource, CatalogueResource, GovernanceResource, AdminEventsResource, GraphResource, PlatformResource, PlatformDocsResource, PlatformIamResource (user mgmt)

---

### Pattern 2: Multi-Role Acceptance (Defined But Unused)
**Location:** REST API layer (capability exists)  
**Method:** `Roles.requireAnyRole(securityContext, role1, role2, ...)`  
**Status:** Available (lines 190-204 of Roles.java) but **not actively used** in current codebase
**Purpose:** Would allow endpoints accessible to multiple roles without requiring SUPER_ADMIN

---

### Pattern 3: Consent-Driven Data Access (Block)
**Location:** Spring Boot service layer  
**Annotation:** `@ConsentRequired`  
**Behavior:**
- Evaluates subject consent for specified scope
- **Blocks method execution** if consent denied
- Throws `ConsentDeniedException`
- Logs all decisions via AuditLogger

**Use Case:** Methods where missing consent is fatal (e.g., accessing PII without permission)

---

### Pattern 4: Sensitive Data Masking
**Location:** Spring Boot service layer  
**Annotation:** `@Sensitive`  
**Behavior:**
- Evaluates subject consent for specified scope
- **Always executes method** (returns value regardless)
- **Masks return value** if consent denied via `MaskingStrategy`
- **Returns unmodified** if consent granted
- Logs all decisions via AuditLogger

**Use Case:** Methods returning mixed public/sensitive data where denial doesn't block access but redacts sensitive fields

---

### Pattern 5: JWT Authentication for Human Users
**Location:** PlatformIamResource IAM endpoints  
**Method:** Custom `requireJwtAuth()` helper  
**Implementation** (Lines 861-881):
```java
private void requireJwtAuth() {
    if (sc != null && sc.isUserInRole(Roles.SUPER_ADMIN)) return; // Override
    if (sc == null || !sc.isUserInRole(requiredRole)) {
        throw new OperioAuthException(...);
    }
}
```
**Behavior:**
- Verifies JWT authentication for human user operations
- SUPER_ADMIN can override org-based checks
- Used for user profile, token, and organization management

---

## 6. KEY AUTHORIZATION CHARACTERISTICS

### Super-Admin Override
- **Definition:** SUPER_ADMIN role implicitly grants all permissions
- **Implementation:** Checked first in `Roles.requireRole()` (Line 168)
- **Bypass:** Returns immediately if SUPER_ADMIN, skipping role checks
- **Effect:** Platform admin doesn't need separate admin endpoints

### Role Consistency
- **Read Pattern:** `ADMIN_READ`, `CONSENT_READ` for GET operations
- **Write Pattern:** `ADMIN_WRITE`, `CONSENT_WRITE` for POST/PUT/DELETE operations
- **Platform Admin:** `SUPER_ADMIN` for tenant/platform management
- **User Management:** `SUPER_ADMIN` (user creation is public, but user mgmt requires admin)

### Public Endpoints (No Auth Required)
1. **Health Check:** `GET /api/v1/health`
2. **User Registration:** `POST /api/v1/platform/iam/users`
3. **Authentication:** `POST /api/v1/platform/iam/login`, `POST /api/v1/platform/iam/refresh`, `POST /api/v1/platform/iam/logout`

### Two-Layer Authorization
1. **Layer 1 — REST API:** RBAC at endpoint entry
2. **Layer 2 — Service:** Consent evaluation in business logic

### Tenant Isolation
- Enforced via `@TenantScoped` interceptor (not explicitly in authorization checks)
- Tenant context required for consent-scoped resources
- Platform-level endpoints ignore tenant context

---

## 7. AUTHORIZATION CHECK LOCATIONS & COUNTS

### By Resource
- **ConsentResource:** 17 checks (9 consent, 8 subject management)
- **CatalogueResource:** 15 checks (5 fields, 5 entities, 5 categories)
- **GovernanceResource:** 19 checks (8 purposes, 6 regulations, 5 jurisdictions)
- **AdminEventsResource:** 1 check
- **GraphResource:** 1 check
- **PlatformResource:** 5 checks (all tenant management)
- **PlatformIamResource:** 10 checks (9 user management, 1 platform)
- **PlatformDocsResource:** 1 check
- **HealthResource:** 0 checks (public)

**Total REST API Checks:** 69

### By Pattern
- **Roles.requireRole():** 69 calls (across all resources)
- **Roles.requireAnyRole():** 0 calls (available but unused)
- **isUserInRole():** 3 calls (Roles.java, PlatformIamResource)
- **@ConsentRequired:** N/A (service layer, not catalogued here)
- **@Sensitive:** N/A (service layer, not catalogued here)

---

## 8. DESIGN FOR COMPREHENSIVE PERMISSIONS MODEL

### What's Already Covered
✅ Resource-level RBAC (endpoint authorization)  
✅ Role hierarchy (tenant roles → API roles)  
✅ Super-admin override pattern  
✅ Consent-based data access (scoped by field, category, purpose)  
✅ Data masking alternative to blocking  
✅ Audit logging for all auth decisions  
✅ Public endpoint support  
✅ JWT human user authentication  

### What to Consider
- **Fine-grained resource ownership:** Current model is resource-type based, not object-instance based
- **Delegation:** No explicit delegation/impersonation pattern visible
- **Time-limited permissions:** No TTL/expiry in RBAC layer (JWT has it at auth layer)
- **Audit trail:** Logged but retention/querying mechanism not in scope here
- **Cross-tenant operations:** Not visible in API layer (assumed blocked by interceptor)

---

## 9. QUICK REFERENCE: ROLE -> ENDPOINT MAPPING

### CONSENT_READ Access
- `POST /api/v1/subjects/{subjectId}/evaluate`
- `GET /api/v1/subjects/{id}/summary`
- `GET /api/v1/subjects/{id}/history`
- `GET /api/v1/consent/graph`

### CONSENT_WRITE Access
- `POST /api/v1/subjects/consent/grant`
- `POST /api/v1/subjects/consent/revoke`
- `POST /api/v1/subjects/consent/grant-all`
- `POST /api/v1/subjects/consent/revoke-all`
- `GET /api/v1/subjects/{id}/export`
- `DELETE /api/v1/subjects/{id}/audit`

### ADMIN_READ Access
- `GET /api/v1/catalogue/fields`
- `GET /api/v1/catalogue/entities`
- `GET /api/v1/catalogue/categories`
- `GET /api/v1/governance/purposes`
- `GET /api/v1/governance/regulations`
- `GET /api/v1/governance/jurisdictions`
- `GET /api/v1/events/invalidations`
- `GET /api/v1/subjects/` (list)

### ADMIN_WRITE Access
- `POST/PUT/DELETE /api/v1/catalogue/*`
- `POST/PUT/DELETE /api/v1/governance/*`
- `POST/PUT/DELETE /api/v1/subjects/{name}` (subject management)

### SUPER_ADMIN Access
- `GET/POST/PUT/DELETE /api/v1/platform/tenants*`
- `GET /api/v1/openapi.json`
- `POST /api/v1/platform/iam/users` (via requireSuperAdmin)
- User management endpoints (list/get/suspend/activate/assign roles)

