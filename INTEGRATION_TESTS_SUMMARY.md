# 🧪 Operio CVEIL REST API - Integration Test Comprehensive Inventory

**Repository:** `/Users/mariahgordon/koostacktech/operio`
**Test Directory:** `cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`

---

## 📊 OVERVIEW

| Metric | Value |
|--------|-------|
| **Total Test Files** | 17 (+ 2 utilities) |
| **Total Test Methods** | 275+ |
| **Nested Test Classes** | 90+ |
| **REST Endpoints Covered** | 80+ |
| **Core Services Integrated** | 8 |
| **Container Types** | 3 (Postgres, Redis, Keycloak) |
| **Framework** | JUnit 5 + Quarkus Test + Testcontainers |

---

## 🛠️ TEST INFRASTRUCTURE

### Test Support Files (Not Test Classes)

**1. CveilTestResourceManager.java** (370 lines)
- Implements: `QuarkusTestResourceLifecycleManager`
- Manages container lifecycle: Postgres 16, Redis 7, Keycloak 26.0.8
- Creates unified test database with all schemas
- Bootstrap method: Creates Keycloak `cveil` realm with `cveil-backend` client
- Injects 8 configuration properties for test environment

**2. IntegrationTestSupport.java** (882 lines)
- Helper library for test assertions
- Provides three verification layers:
  - **JDBC**: Direct database queries with tenant-aware schema routing
  - **Redis**: Jedis pooled client for cache/stream access
  - **Keycloak**: Admin API client for realm management
- Key utilities:
  - Database state assertions (`assertRowExists`, `assertRowAbsent`)
  - Event store queries (`queryEvents`, `queryEventsByAggregate`)
  - Redis operations (`getStreamEntries`, `queryRedisKey`)
  - Keycloak polling (`awaitKeycloakRealmExists`, `awaitKeycloakUserExists`)
  - Tenant provisioning (`provisionTenantAndLogin`, `awaitTenantReady`)

---

## 📚 DETAILED TEST FILES

### Tier 1: DSAR (Data Subject Access Request) - GDPR/CCPA Compliance

#### 🔷 **ConsentDsarIntegrationIT.java** (361 lines)
- **Purpose:** Test DSAR endpoints (GDPR Articles 15, 17, 20 + CCPA §1798.100/105)
- **Class:** `ConsentDsarIntegrationIT`
- **REST Endpoints:**
  - `POST /api/v1/subjects/consent/grant` - Grant consent scope
  - `POST /api/v1/subjects/consent/revoke` - Revoke consent scope
  - `GET /api/v1/subjects/{id}/export` - Subject data export
  - `DELETE /api/v1/subjects/{id}?confirm={token}` - Subject deletion
- **Test Methods (10):**
  1. `testExportCompleteness()` - Multiple policies → export returns all
  2. `testExportEmptySubject()` - No consent → empty lists
  3. `testExportAuditTrail()` - Grant/revoke → audit trail in order
  4. `testDeleteMissingConfirmation()` - No confirm → 400
  5. `testDeleteWrongConfirmation()` - Wrong token → 400
  6. `testDeleteAtomicity()` - All stores cleared (Postgres + Redis + audit)
  7. `testDeleteIdempotency()` - DELETE twice → both succeed
  8. `testDeleteAuditLogging()` - Deletion logged in dsar_audit_log
  9. `testDeleteAuthorization()` - No auth → 401
  10. `testExportAuthorization()` - No auth → 401

#### 🔷 **DsarTenantIsolationIT.java** (392 lines)
- **Purpose:** Multi-tenant isolation for DSAR
- **Class:** `DsarTenantIsolationIT`
- **Test Methods (3):**
  1. `testExportTenantIsolation()` - Tenant A export isolated from Tenant B
  2. `testDeleteTenantIsolation()` - Tenant A delete doesn't affect Tenant B
  3. `testDsarAuditPerTenant()` - Audit log entries tenant-scoped

---

### Tier 2: Infrastructure & Configuration

#### 🔶 **PostgresSslIntegrationIT.java** (161 lines)
- **Purpose:** PostgreSQL JDBC SSL/TLS configuration
- **Class:** `PostgresSslIntegrationIT`
- **Test Methods (4 direct + 3 in comments):**
  1. `testPlaintextPostgresConnection()` - URL without SSL uses sslmode=disable
  2. `testSslModeParameterAppended()` - SSL mode appended correctly
  3. `testMultipleSslParametersAppended()` - Multiple SSL params handled
  4. `testExistingParametersPreserved()` - Query params not overwritten
  5. `testNonPostgresUrlUnmodified()` - Non-Postgres URLs unchanged
  6. `testSslDisableModeBackwardCompatibility()` - Legacy mode works
  7. `testVerifyFullModeHostnameValidation()` - Hostname validation

#### 🔶 **RedisTlsIntegrationIT.java** (174 lines)
- **Purpose:** Redis TLS configuration and RedisClientFactory
- **Class:** `RedisTlsIntegrationIT`
- **Test Methods (3 direct + 5 in comments):**
  1. `testPlaintextRedisConnection()` - Baseline plaintext connection
  2. `testTlsConfigurationBuilder()` - TLS config construction
  3. `testRedisClientFactoryWithTlsConfig()` - Factory + TLS parameter
  4. `testRedisClientFactoryWithPasswordAndTls()` - Factory + password + TLS
  5. `testRedisClientFactoryWithAclAndTls()` - Factory + ACL + password + TLS

---

### Tier 3: Catalogue Service

#### 🟦 **CatalogueResourceIT.java** (916 lines)
- **Purpose:** Full CRUD lifecycle for data catalogue
- **Class:** `CatalogueResourceIT`
- **REST Endpoints:**
  - `POST /api/v1/catalogue/entities`
  - `GET /api/v1/catalogue/entities`, `/entities/{name}`
  - `PUT /api/v1/catalogue/entities/{name}`
  - `DELETE /api/v1/catalogue/entities/{name}`
  - Same pattern for `/categories` and `/fields`
  - `POST /api/v1/catalogue/fields/{name}/assign-category/{category}`
  - `DELETE /api/v1/catalogue/fields/{name}/unassign-category/{category}`
- **Nested Test Classes (6):**
  1. **SetupAndAuth** (3 tests)
     - Create tenant & login
     - Reject unauthenticated requests (401)
     - Reject invalid token (401)
  2. **EntityCrud** (10 tests)
     - Register User → 201
     - List entities
     - Get entity
     - Update entity description
     - Register Patient
     - Delete Patient
     - 404 on deleted entity
     - Idempotent delete
     - Reject blank name → 400
     - Upsert (re-register with description update)
  3. **CategoryCrud** (7 tests)
     - Similar CRUD for IT_PII, IT_HEALTH categories
  4. **FieldAndCategoryAssignment** (6 tests)
     - Register fields (email, phone, ssn)
     - Assign fields to categories
     - List field-category relationships
     - Unassign category from field
  5. **DeletionConstraints** (2 tests)
     - 409 when deleting category with assigned field
     - 409 when deleting entity with linked policy
  6. **OutboxVerification** (5 tests)
     - Entity events exist in store
     - Category events exist
     - Field events exist
     - Redis stream has catalogue events
- **Verification Layers:**
  - REST response (status, body)
  - Postgres direct query (entity table)
  - Event store (consent_events table)
  - Redis cache (admin projection)

---

### Tier 4: Governance Service

#### 🟩 **GovernanceResourceIT.java** (1,318 lines)
- **Purpose:** Full CRUD lifecycle for governance (purposes, regulations, jurisdictions, policies)
- **Class:** `GovernanceResourceIT`
- **REST Endpoints:**
  - `POST /api/v1/governance/purposes`, `/regulations`, `/jurisdictions`
  - `GET /api/v1/governance/purposes`, `/regulations`, `/jurisdictions`
  - `GET /api/v1/governance/purposes/{name}` (and same for regulations, jurisdictions)
  - `PUT /api/v1/governance/purposes/{name}` (and same for regulations, jurisdictions)
  - `DELETE /api/v1/governance/purposes/{name}` (and same for regulations, jurisdictions)
  - `POST /api/v1/governance/purposes/{purpose}/regulations/{regulation}` - Link purpose to regulation
  - `POST /api/v1/governance/policies` - Add purpose policies
  - `DELETE /api/v1/governance/policies` - Remove policies
- **Nested Test Classes (9):**
  1. **SetupAndAuth** (3 tests)
  2. **PurposeCrud** (8 tests)
     - Register marketing, analytics purposes
     - List purposes
     - Get purpose
     - Update purpose
     - Delete purpose
     - Re-register (upsert)
     - Reject blank name → 400
     - 404 on non-existent
  3. **RegulationCrud** (8 tests) - Similar to Purpose
  4. **JurisdictionCrud** (6 tests)
  5. **PurposePolicyManagement** (8 tests)
     - Add purpose policy (field-level scope)
     - Verify policy exists in projections
     - Update policy
     - Delete policy
     - Handle conflicts
  6. **PurposeRegulationLinking** (6 tests)
     - Link marketing → GDPR
     - Link analytics → CCPA
     - Unlink
     - 409 on delete linked purpose
  7. **RegulationJurisdictionLinking** (5 tests)
  8. **DeletionConstraints** (3 tests)
  9. **EventStoreVerification** (4 tests)

---

### Tier 5: Consent Service

#### 🟪 **ConsentResourceIT.java** (1,662 lines)
- **Purpose:** Core consent grant/revoke/evaluate operations with multi-subject isolation
- **Class:** `ConsentResourceIT`
- **REST Endpoints:**
  - `POST /api/v1/subjects/{id}` - Register subject
  - `DELETE /api/v1/subjects/{id}` - Remove subject
  - `POST /api/v1/subjects/{id}/consent/grant` - Grant consent for scope
  - `POST /api/v1/subjects/{id}/consent/revoke` - Revoke consent
  - `GET /api/v1/subjects/{id}/evaluate?...` - Evaluate scope evaluation
  - `GET /api/v1/subjects/{id}/summary` - Subject consent summary
  - `GET /api/v1/subjects/{id}/history` - Subject consent history
  - `POST /api/v1/subjects/batch/grant` - Batch grant
  - `POST /api/v1/subjects/batch/revoke` - Batch revoke
- **Nested Test Classes (11):**
  1. **PrerequisitesSetup** - Register tenant, entities, categories, fields, purposes, regulations
  2. **SubjectManagement** - Register/remove subjects
  3. **SingleConsentGrant** - Grant one policy, verify DB + Redis + audit
  4. **SingleConsentRevoke** - Revoke policy, verify cleanup
  5. **BatchConsentOps** - Grant/revoke multiple subjects
  6. **SubjectSummaryAndHistory** - Query summary/history with correct counts
  7. **ConsentExpiration** - Test expiring consents
  8. **MultiSubjectIsolation** - Subject A can't see Subject B's consent
  9. **CrossResourceValidation** - Verify consistency across services
  10. **DsarVerification** - Export includes correct consent entries
  11. **OutboxConsistency** - Event stream and outbox aligned

#### 🟪 **ConsentGraphProjectionIT.java** (845 lines)
- **Purpose:** Tests graph projection of consent relationships
- **Class:** `ConsentGraphProjectionIT`
- **REST Endpoints:** Same as ConsentResourceIT + graph queries
- **Nested Test Classes (7):**
  1. **Setup** - Provision tenant + Keycloak login
  2. **DataSetup** - Register catalogue (Customer entity, Personal category, email field) + governance (marketing purpose, GDPR regulation)
  3. **CatalogueGraphNodes** - Verify catalogue node relationships
  4. **GovernanceGraphNodes** - Verify governance node relationships
  5. **GrantConsentGraph** - Grant consent, verify graph updated
  6. **RevokeConsentGraph** - Revoke, verify graph pruned
  7. **SubjectErasureGraph** - Delete subject, verify complete graph cleanup

---

### Tier 6: Platform IAM (Authentication & Authorization)

#### 🟫 **PlatformIamResourceIT.java** (737 lines)
- **Purpose:** Platform-level user management, role management, RBAC, authentication
- **Class:** `PlatformIamResourceIT`
- **REST Endpoints:**
  - `POST /api/v1/platform/iam/users` - Register platform user → creates tenant
  - `GET /api/v1/platform/iam/users` - List users
  - `GET /api/v1/platform/iam/users/{userId}` - Get user
  - `PUT /api/v1/platform/iam/users/{userId}` - Update user
  - `DELETE /api/v1/platform/iam/users/{userId}` - Disable user (soft delete)
  - `POST /api/v1/platform/iam/users/{userId}/roles` - Assign role
  - `DELETE /api/v1/platform/iam/users/{userId}/roles/{roleName}` - Revoke role
  - `POST /api/v1/platform/iam/users/{userId}/service-accounts` - Create service account
  - Token endpoint via Keycloak
- **Nested Test Classes (7):**
  1. **RealmBootstrap** - Verify cveil realm, cveil-backend client, default roles
  2. **UserRegistration** (6 tests)
     - Register user → 201 with tenantId from username
     - Duplicate registration rejected
     - Invalid email → 400
     - Missing email → 400
     - User materializes in Keycloak
     - Registration event stored
  3. **UserLifecycle** - Update, disable, enable users
  4. **RoleManagement** - Assign/revoke roles
  5. **ServiceAccounts** - Create service account credentials
  6. **Authentication** - ROPC token grant, verify token claims
  7. **Rbac** - Enforce role-based access control on endpoints

---

### Tier 7: Tenant IAM (Unified IAM per Tenant)

#### 🟥 **TenantIamResourceIT.java** (980 lines)
- **Purpose:** Tenant-scoped user management (within unified Keycloak realm)
- **Class:** `TenantIamResourceIT`
- **REST Endpoints:**
  - `POST /api/v1/tenants/{tenantId}/iam/users` - Register tenant user
  - `GET /api/v1/tenants/{tenantId}/iam/users` - List tenant users
  - Similar update/delete/roles for tenant scope
- **Nested Test Classes (9):**
  1. **Provisioning** - Provision tenant via orchestrator
  2. **UserRegistration** - Register tenant user → 201
  3. **UserLifecycle** - Update, disable, enable within tenant
  4. **RoleManagement** - Assign/revoke tenant roles
  5. **ServiceAccounts** - Service accounts per tenant
  6. **Authentication** - Token grant with tenant scoping
  7. **CrossTenantIsolation** - Tenant A users isolated from Tenant B
  8. **Rbac** - RBAC enforcement within tenant
  9. **Cleanup** - Delete test tenants

---

### Tier 8: Tenant Creation Pipeline

#### 🟨 **CreateTenantPipelineIT.java** (476 lines)
- **Purpose:** E2E tenant creation workflow with unified schema and RLS
- **Class:** `CreateTenantPipelineIT`
- **REST Endpoints:**
  - `POST /api/v1/platform/iam/users` - User registration (triggers tenant creation)
- **Nested Test Classes (7):**
  1. **UserRegistrationHttp** (2 tests)
     - Register user → 201 with tenantId derived from username
     - Duplicate registration rejected
  2. **EventStoreValidation** (2 tests)
     - TenantCreated event in event store
     - Billing events exist (ChargeableOperationStarted, etc.)
  3. **ProjectionValidation** (4 tests)
     - Tenant row materializes in cveiliam.tenant
     - Billing profile created
     - Subscription row with plan limits
     - Cross-projection consistency
  4. **UnifiedSchemaValidation** - All consent tables exist in cveilconsent schema
  5. **TenantStatusValidation** - Tenant status reflects creation
  6. **KeycloakLogin** (2 tests)
     - Keycloak login succeeds
     - JWT contains correct claims
  7. **PlanValidation** - Plan/subscription limits correct

---

### Tier 9: Redis ACL Tenant Isolation

#### 🔴 **RedisTenantAclIsolationIT.java** (1,057 lines)
- **Purpose:** Verify Redis ACL tenant isolation at infrastructure level
- **Class:** `RedisTenantAclIsolationIT`
- **Infrastructure Testing:** Direct Redis ACL (no REST endpoints)
- **Nested Test Classes (10):**
  1. **Setup** - Provision tenants A & B, resolve Redis credentials
  2. **WriteIsolation** (2 tests)
     - Tenant A can write to tenant_a namespace
     - Tenant A CANNOT write to tenant_b namespace
  3. **ReadIsolation** (2 tests)
     - Tenant A can read own keys
     - Tenant A CANNOT read Tenant B keys
  4. **DeleteIsolation** (2 tests)
     - Tenant A CANNOT DEL from Tenant B namespace
  5. **PlatformKeyIsolation** - Tenant cannot access ratelimit:* keys
  6. **AdminAccess** - Default user can access all keys
  7. **AclReprovisioning** - Credential rotation works
  8. **CommandRestrictions** - @admin, @dangerous commands denied
  9. **EndToEndConsentIsolation** - REST operations respect isolation
  10. **Cleanup** - Delete test tenants

---

### Tier 10: Cache Invalidation & Pub/Sub

#### 🟢 **CacheInvalidationIT.java** (691 lines)
- **Purpose:** Cache invalidation when catalogue/governance changes
- **Class:** Not extracted (uses nested classes)
- **REST Endpoints:**
  - All catalogue endpoints (POST, PUT, DELETE)
  - All governance endpoints (POST, PUT, DELETE)
  - `GET /api/v1/sse/{tenantId}` - Server-sent events stream
- **Nested Test Classes (6):**
  1. **Setup** - Create tenant, login, start Pub/Sub subscriber
  2. **CatalogueInvalidations** (3 tests)
     - Entity registration publishes invalidation
     - Field registration publishes invalidation
     - Category registration publishes invalidation
  3. **GovernanceInvalidations** (2 tests)
     - Purpose registration publishes invalidation
     - Regulation registration publishes invalidation
  4. **ConsentInvalidations** (2 tests)
     - Consent grant publishes GRANTED event
     - Consent revoke publishes REVOKED event
  5. **SseAuth** - SSE endpoint requires authentication
  6. **SseStreamDelivery** - SSE delivers invalidation events to subscribers

---

### Tier 11: Graph Query API

#### 🔵 **GraphResourceIT.java** (531 lines)
- **Purpose:** Test graph query API for navigating consent relationships
- **Class:** Not extracted
- **REST Endpoints:**
  - `POST /api/v1/graph/query` - Execute graph query
  - All prerequisite endpoints (catalogue, governance, consent)
- **Nested Test Classes (4):**
  1. **Setup** - Provision tenant, login
  2. **DataSetup** - Register all prerequisites (entities, categories, fields, purposes, regulations, policies, consent)
  3. **GraphQuery** - Execute graph queries, verify results
  4. **AuthEnforcement** - Graph endpoint requires authentication

---

### Tier 12: Subject Lifecycle

#### 🟡 **SubjectLifecycleIT.java** (690 lines)
- **Purpose:** Subject lifecycle: register, remove, DSAR export, key shredding
- **Class:** Not extracted
- **REST Endpoints:**
  - `POST /api/v1/subjects/{id}` - Register subject
  - `DELETE /api/v1/subjects/{id}` - Remove subject
  - `POST /api/v1/subjects/{id}/dsar-key/shred` - Shred DSAR encryption key
  - All consent endpoints (for grant/revoke/export)
- **Nested Test Classes (5):**
  1. **Setup** - Provision tenant, login
  2. **Prerequisites** - Register catalogue, governance
  3. **RemoveSubject** - Remove subject endpoint
  4. **ShredDsarKey** - Shred DSAR key operation
  5. **DsarDeepVerification** - Deep verification of DSAR export correctness

---

### Tier 13: Platform Lifecycle

#### 🟠 **PlatformLifecycleIT.java** (283 lines)
- **Purpose:** Platform-level lifecycle: plan updates, tenant deletion
- **Class:** Not extracted
- **REST Endpoints:**
  - `PATCH /api/v1/platform/tenants/{tenantId}/plan` - Update plan (free → pro → free)
  - `DELETE /api/v1/platform/tenants/{tenantId}` - Delete tenant
- **Nested Test Classes (3):**
  1. **Setup** - Initialize test support, provision tenants
  2. **UpdatePlan** (5 tests)
     - Update plan free → pro
     - Verify projection updated
     - Update back to free
     - Invalid plan handled gracefully
     - Non-existent tenant handled gracefully
  3. **DeleteTenant** (3 tests)
     - Verify tenant exists before deletion
     - Delete non-existent tenant (idempotent)
     - Delete tenant operation

---

### Tier 14: End-to-End Production Stack

#### ⚪ **ProductionStackIT.java** (1,674 lines)
- **Purpose:** Comprehensive end-to-end integration test of full production stack
- **Class:** `ProductionStackIT`
- **REST Endpoints:** All endpoints (comprehensive)
- **Nested Test Classes (12):**
  1. **ContainerStartup** (5 tests)
     - Quarkus container starts
     - CveilConfig injected
     - Production mode resolved
     - TokenValidationService wired
     - DB credentials validated
  2. **HealthEndpoints** (5 tests)
     - /health endpoint
     - /q/health endpoint
     - Readiness probe
     - Liveness probe
     - OpenAPI spec served
  3. **AuthEnforcement** (3 tests)
     - Unauthenticated → 401
     - Invalid token → 401
     - Expired token → 401
  4. **PlatformLifecycle** (2 tests)
     - Tenant creation via user registration
     - Keycloak login succeeds
  5. **AdminSchemaRegistration** (3 tests)
     - Admin registers consent schema
     - Schema enforcement works
  6. **ConsentSingleOps** (4 tests)
     - Grant consent
     - Evaluate consent
     - Revoke consent
     - Verify state changes
  7. **ConsentBatchOps** (3 tests)
     - Batch grant multiple
     - Batch revoke
     - Verify all states
  8. **SubjectData** (2 tests)
     - Subject summary endpoint
     - Subject history endpoint
  9. **AuthEnforcementWithToken** (3 tests)
     - Valid token operations succeed
     - Malformed token → 401
     - Token expiration handled
  10. **RequestValidation** (4 tests)
      - Invalid request body → 400
      - Missing required fields → 400
      - Type mismatches → 400
  11. **MultiSubjectIsolation** (3 tests)
      - Subject A isolated from B
      - No data leakage
  12. **FinalHealthCheck** - Final verification of system health

---

## 🎯 REST ENDPOINTS TESTED

### Catalogue Endpoints
```
POST   /api/v1/catalogue/entities
GET    /api/v1/catalogue/entities
GET    /api/v1/catalogue/entities/{name}
PUT    /api/v1/catalogue/entities/{name}
DELETE /api/v1/catalogue/entities/{name}
POST   /api/v1/catalogue/categories
GET    /api/v1/catalogue/categories
GET    /api/v1/catalogue/categories/{name}
PUT    /api/v1/catalogue/categories/{name}
DELETE /api/v1/catalogue/categories/{name}
POST   /api/v1/catalogue/fields
GET    /api/v1/catalogue/fields
GET    /api/v1/catalogue/fields/{name}
DELETE /api/v1/catalogue/fields/{name}
POST   /api/v1/catalogue/fields/{name}/assign-category/{category}
DELETE /api/v1/catalogue/fields/{name}/unassign-category/{category}
```

### Governance Endpoints
```
POST   /api/v1/governance/purposes
GET    /api/v1/governance/purposes
GET    /api/v1/governance/purposes/{name}
PUT    /api/v1/governance/purposes/{name}
DELETE /api/v1/governance/purposes/{name}
POST   /api/v1/governance/regulations
GET    /api/v1/governance/regulations
... (similar for regulations)
POST   /api/v1/governance/jurisdictions
GET    /api/v1/governance/jurisdictions
... (similar for jurisdictions)
POST   /api/v1/governance/policies
DELETE /api/v1/governance/policies
POST   /api/v1/governance/purposes/{purpose}/regulations/{regulation}
DELETE /api/v1/governance/purposes/{purpose}/regulations/{regulation}
```

### Consent Endpoints
```
POST   /api/v1/subjects/{id}
DELETE /api/v1/subjects/{id}
GET    /api/v1/subjects/{id}
GET    /api/v1/subjects/{id}/summary
GET    /api/v1/subjects/{id}/history
GET    /api/v1/subjects/{id}/evaluate
POST   /api/v1/subjects/{id}/consent/grant
POST   /api/v1/subjects/{id}/consent/revoke
POST   /api/v1/subjects/batch/grant
POST   /api/v1/subjects/batch/revoke
```

### DSAR Endpoints
```
POST   /api/v1/subjects/consent/grant
POST   /api/v1/subjects/consent/revoke
GET    /api/v1/subjects/{id}/export
DELETE /api/v1/subjects/{id}?confirm={token}
POST   /api/v1/subjects/{id}/dsar-key/shred
```

### Platform IAM Endpoints
```
POST   /api/v1/platform/iam/users
GET    /api/v1/platform/iam/users
GET    /api/v1/platform/iam/users/{userId}
PUT    /api/v1/platform/iam/users/{userId}
DELETE /api/v1/platform/iam/users/{userId}
POST   /api/v1/platform/iam/users/{userId}/roles
DELETE /api/v1/platform/iam/users/{userId}/roles/{roleName}
GET    /api/v1/platform/iam/users/{userId}/service-accounts
POST   /api/v1/platform/iam/users/{userId}/service-accounts
```

### Tenant IAM Endpoints
```
POST   /api/v1/tenants/{tenantId}/iam/users
GET    /api/v1/tenants/{tenantId}/iam/users
GET    /api/v1/tenants/{tenantId}/iam/users/{userId}
PUT    /api/v1/tenants/{tenantId}/iam/users/{userId}
DELETE /api/v1/tenants/{tenantId}/iam/users/{userId}
```

### Platform Endpoints
```
PATCH  /api/v1/platform/tenants/{tenantId}/plan
DELETE /api/v1/platform/tenants/{tenantId}
```

### Graph Endpoints
```
POST   /api/v1/graph/query
GET    /api/v1/sse/{tenantId}
```

### Health Endpoints
```
GET    /health
GET    /q/health
GET    /q/health/ready
GET    /q/health/live
GET    /openapi
```

---

## 📋 NESTED TEST CLASS REFERENCE

| File | Class | Tests | Coverage |
|------|-------|-------|----------|
| CatalogueResourceIT | SetupAndAuth | 3 | Auth enforcement |
| | EntityCrud | 10 | Entity CRUD |
| | CategoryCrud | 7 | Category CRUD |
| | FieldAndCategoryAssignment | 6 | Field-category linking |
| | DeletionConstraints | 2 | Constraint validation |
| | OutboxVerification | 5 | Event consistency |
| GovernanceResourceIT | SetupAndAuth | 3 | Auth enforcement |
| | PurposeCrud | 8 | Purpose CRUD |
| | RegulationCrud | 8 | Regulation CRUD |
| | JurisdictionCrud | 6 | Jurisdiction CRUD |
| | PurposePolicyManagement | 8 | Policy management |
| | PurposeRegulationLinking | 6 | Governance linking |
| | RegulationJurisdictionLinking | 5 | More linking |
| | DeletionConstraints | 3 | Constraint validation |
| | EventStoreVerification | 4 | Event consistency |
| ConsentResourceIT | PrerequisitesSetup | 10+ | Setup |
| | SubjectManagement | 4+ | Subject ops |
| | SingleConsentGrant | 5+ | Grant operations |
| | SingleConsentRevoke | 5+ | Revoke operations |
| | BatchConsentOps | 5+ | Batch operations |
| | SubjectSummaryAndHistory | 4+ | Query endpoints |
| | ConsentExpiration | 4+ | Expiration logic |
| | MultiSubjectIsolation | 5+ | Isolation |
| | CrossResourceValidation | 5+ | Cross-service |
| | DsarVerification | 4+ | Export correctness |
| | OutboxConsistency | 4+ | Event consistency |

---

## 🔐 Test Authentication & Authorization

### Bootstrap Key
- **Key:** `test-bootstrap-key-for-integration-tests`
- **Used For:** Initial tenant creation, schema setup in early tests
- **Scope:** Platform-level operations

### Keycloak Integration
- **Realm:** `cveil` (created per test)
- **Client:** `cveil-backend` (confidential, ROPC + service-account)
- **Grant Types:** 
  - Resource Owner Password Credentials (ROPC) for user login
  - Service Account (client credentials) for service-to-service
- **Default Roles:** consent:read, consent:write, admin:read, admin:write, tenant-admin, tenant-write, tenant-read, super-admin
- **Per-Test Isolation:** Each test creates new tenant → new Keycloak realm (except unified tests)

### Per-Test Tenant Isolation
- **Schema Routing:** JDBC `SET search_path TO cveilconsent, cveilevent, cveilcore, public`
- **Redis ACL:** Each tenant has namespace-based isolation (verified in RedisTenantAclIsolationIT)
- **Keycloak Realms:** 
  - Platform realm: `cveil` (shared)
  - Tenant realms: `{tenantId}` (per tenant in TenantIamResourceIT)

---

## 📈 Test Execution & Ordering

### Deterministic Ordering
- `@TestClassOrder(ClassOrderer.OrderAnnotation.class)` - Nested classes execute in @Order sequence
- `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` - Methods execute in @Order sequence
- **Why?** Tests have dependencies (e.g., setup before queries)

### Static State Sharing
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` - Single instance per test class
- **Static Variables:** accessToken, support, tenantId
- **Rationale:** Share expensive setup (tenant provisioning, Keycloak login) across tests

### Polling & Eventual Consistency
- `Awaitility.await()` - Poll until condition or timeout
- **Common Patterns:**
  - `awaitTenantReady(tenantId)` - Poll until tenant schema exists
  - `awaitKeycloakRealmExists(realmName)` - Poll until realm created
  - `awaitKeycloakUserExists(realmName, email)` - Poll until user CQRS projected
  - `awaitQueryValue()` - Poll until DB value updates
  - `awaitGovernanceProjection()` - Poll until policies visible in DB
  - Timeout: Typically 10-90 seconds per test

---

## 🛡️ Verification Layers

### Layer 1: REST API Response
```java
given()
  .header("Authorization", "Bearer " + token)
  .contentType(ContentType.JSON)
  .body(payload)
  .when()
  .post("/api/v1/catalogue/entities")
  .then()
  .statusCode(201)
  .body("message", containsString("user"))
```

### Layer 2: Database State
```java
support.assertRowExists("entity", "entity_name", "user");
support.assertRowAbsent("category", "category_name", "deleted");
support.awaitQueryValue(
  "SELECT description FROM entity WHERE entity_name = ?",
  "description",
  "Updated user entity",
  "user"
);
```

### Layer 3: Event Store
```java
var events = support.queryEvents("consent-entity", "consent.entity.registered");
assertThat(events).isNotEmpty();
```

### Layer 4: Redis Cache & Streams
```java
var entries = support.getStreamEntries(TENANT_ID, 1000);
assertThat(entries).isNotEmpty();
```

---

## 📁 Files Summary

| File | Lines | Type | Tests/Methods | Purpose |
|------|-------|------|---|---------|
| ConsentDsarIntegrationIT.java | 361 | Direct | 10 | DSAR compliance (GDPR/CCPA) |
| DsarTenantIsolationIT.java | 392 | Direct | 3 | DSAR multi-tenant isolation |
| PostgresSslIntegrationIT.java | 161 | Direct | 7 | Postgres SSL configuration |
| RedisTlsIntegrationIT.java | 174 | Direct | 5 | Redis TLS configuration |
| CatalogueResourceIT.java | 916 | Nested | 33 | Catalogue CRUD |
| GovernanceResourceIT.java | 1,318 | Nested | 51 | Governance CRUD |
| ConsentResourceIT.java | 1,662 | Nested | 55+ | Consent operations |
| ConsentGraphProjectionIT.java | 845 | Nested | 30+ | Graph projection |
| ProductionStackIT.java | 1,674 | Nested | 60+ | End-to-end |
| CreateTenantPipelineIT.java | 476 | Nested | 21 | Tenant creation |
| PlatformIamResourceIT.java | 737 | Nested | 35 | Platform IAM |
| TenantIamResourceIT.java | 980 | Nested | 40 | Tenant IAM |
| RedisTenantAclIsolationIT.java | 1,057 | Nested | 45 | Redis isolation |
| CacheInvalidationIT.java | 691 | Nested | 30 | Pub/Sub invalidation |
| GraphResourceIT.java | 531 | Nested | 25 | Graph queries |
| SubjectLifecycleIT.java | 690 | Nested | 25 | Subject lifecycle |
| PlatformLifecycleIT.java | 283 | Nested | 13 | Platform lifecycle |
| **CveilTestResourceManager.java** | **370** | **Utility** | **N/A** | Container management |
| **IntegrationTestSupport.java** | **882** | **Utility** | **N/A** | Test helpers |

---

## 📚 Complete File List

```
cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/
├── CacheInvalidationIT.java
├── CatalogueResourceIT.java
├── ConsentDsarIntegrationIT.java
├── ConsentGraphProjectionIT.java
├── ConsentResourceIT.java
├── CreateTenantPipelineIT.java
├── CveilTestResourceManager.java       ← Support utility (not a test)
├── DsarTenantIsolationIT.java
├── GovernanceResourceIT.java
├── GraphResourceIT.java
├── IntegrationTestSupport.java          ← Support utility (not a test)
├── PlatformIamResourceIT.java
├── PlatformLifecycleIT.java
├── PostgresSslIntegrationIT.java
├── ProductionStackIT.java
├── RedisTenantAclIsolationIT.java
├── RedisTlsIntegrationIT.java
├── SubjectLifecycleIT.java
└── TenantIamResourceIT.java
```

---

## 🎓 Related Documentation

For more details, see:
- **Detailed Inventory:** `INTEGRATION_TEST_INVENTORY_DETAILED.md`
- **Quick Reference:** `INTEGRATION_TEST_INVENTORY_QUICK.txt`

