# COMPREHENSIVE INTEGRATION TEST INVENTORY

**Location:** `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`

**Framework:** JUnit 5 with RestAssured, Quarkus Test, Testcontainers

**Total Test Files:** 19 (including 2 utilities)
**Active Test Files:** 17 (excluding utilities)

---

## 📋 TEST SUPPORT FILES & UTILITIES

### 1. **CveilTestResourceManager.java**
- **Location:** `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/CveilTestResourceManager.java`
- **Type:** Test Resource Manager (NOT a test class)
- **Purpose:** Manages Testcontainers lifecycle (Postgres, Redis, Keycloak)
- **Key Methods:**
  - `start()` - Starts Docker containers and injects config properties
  - `stop()` - Tears down containers
  - `bootstrapPlatformRealm()` - Sets up Keycloak realm
- **Container Management:**
  - PostgreSQL 16-Alpine with unified schema (cveilcore, cveiliam, cveilbilling, cveilevent, cveilconsent, cveilmetrics)
  - Redis 7-Alpine
  - Keycloak 26.0.8
- **Injected Properties:**
  - `cveil.test.db.url`, `cveil.test.db.username`, `cveil.test.db.password`
  - `cveil.test.redis.host`, `cveil.test.redis.port`
  - `cveil.test.keycloak.url`, `cveil.test.keycloak.admin-username`, `cveil.test.keycloak.admin-password`, `cveil.test.keycloak.client-secret`

### 2. **IntegrationTestSupport.java**
- **Location:** `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/IntegrationTestSupport.java`
- **Type:** Shared Test Utilities (NOT a test class)
- **Purpose:** Direct database and Redis access for test assertions
- **Key Features:**
  - JDBC connections with automatic schema search_path routing
  - Redis Jedis pooled connections
  - Keycloak Admin API client
  - Event stream querying and verification
- **Key Methods (Selection):**
  - `openConnection()` - Opens JDBC connection with tenant schema routing
  - `queryEvents()`, `queryEventsByAggregate()` - Query event store
  - `assertRowExists()`, `assertRowAbsent()` - Database assertions
  - `getStreamEntries()` - Redis stream access
  - `awaitKeycloakRealmExists()`, `awaitKeycloakUserExists()` - Keycloak polling
  - `bootstrapKeycloakRealm()` - Setup Keycloak realm for tests
  - `provisionTenantAndLogin()` - Static helper for tenant provisioning
  - `awaitTenantReady()` - Poll until tenant is ready

---

## 🧪 ACTIVE TEST FILES & METHODS

### 1. **ConsentDsarIntegrationIT.java**
- **Class:** `ConsentDsarIntegrationIT`
- **Display Name:** "DSAR (Data Subject Access Request) Integration Tests"
- **Purpose:** Tests Data Subject Access Request (GDPR Article 15, 17, 20; CCPA 1798.100/105) endpoints
- **REST Endpoints Tested:**
  - `POST /api/v1/subjects/consent/grant` - Grant consent
  - `POST /api/v1/subjects/consent/revoke` - Revoke consent  
  - `GET /api/v1/subjects/{id}/export` - Export subject data
  - `DELETE /api/v1/subjects/{id}` - Delete subject with confirmation
- **Test Methods (10):**
  1. `testExportCompleteness()` - Grant multiple policies, export, verify all returned
  2. `testExportEmptySubject()` - Export subject with no consent, verify empty lists
  3. `testExportAuditTrail()` - Grant, revoke, export, verify audit entries in correct order
  4. `testDeleteMissingConfirmation()` - DELETE without confirm param → 400
  5. `testDeleteWrongConfirmation()` - DELETE with wrong confirm token → 400
  6. `testDeleteAtomicity()` - Grant + cache, DELETE, verify all stores cleared
  7. `testDeleteIdempotency()` - DELETE twice, both succeed
  8. `testDeleteAuditLogging()` - DELETE, verify entry in dsar_audit_log with counts
  9. `testDeleteAuthorization()` - Call without valid credentials → 401
  10. `testExportAuthorization()` - Call without valid credentials → 401
- **Key Features:**
  - Uses bootstrap key for test authentication
  - Tests atomicity across Postgres, Redis, and audit stores
  - Verifies GDPR/CCPA compliance scenarios

---

### 2. **DsarTenantIsolationIT.java**
- **Class:** `DsarTenantIsolationIT`
- **Display Name:** "DSAR Tenant Isolation"
- **Purpose:** Multi-tenant isolation for DSAR operations
- **Test Methods (3):**
  1. `testExportTenantIsolation()` - Export in tenant A should not return data from tenant B
  2. `testDeleteTenantIsolation()` - Delete in tenant A should not affect tenant B data
  3. `testDsarAuditPerTenant()` - DSAR audit log entries should be per-tenant
- **REST Endpoints Tested:** Same as ConsentDsarIntegrationIT (scoped by tenant)

---

### 3. **PostgresSslIntegrationIT.java**
- **Class:** `PostgresSslIntegrationIT`
- **Display Name:** "PostgreSQL SSL/TLS Integration Tests"
- **Purpose:** Tests PostgreSQL JDBC URL SSL/TLS configuration
- **Test Methods (4):** (Note: Full list includes 7 in comments, but regex extracted 4)
  1. `testSslModeParameterAppended()` - SSL mode parameter correctly appended to JDBC URL
  2. `testExistingParametersPreserved()` - Existing query parameters not overwritten
  3. `testSslDisableModeBackwardCompatibility()` - SSL disable mode works for backward compatibility
  4. `testVerifyFullModeHostnameValidation()` - Verify-full mode validates hostname
- **Infrastructure:** Direct PostgreSQL SSL configuration testing (no REST endpoints)

---

### 4. **RedisTlsIntegrationIT.java**
- **Class:** `RedisTlsIntegrationIT`
- **Display Name:** "Redis TLS Integration Tests"
- **Purpose:** Tests Redis TLS configuration and RedisClientFactory
- **Test Methods (3):** (Full list in file is 5)
  1. `testTlsConfigurationBuilder()` - TLS config builder doesn't throw during construction
  2. `testRedisClientFactoryWithPasswordAndTls()` - RedisClientFactory accepts password + TLS
  3. `testRedisClientFactoryWithAclAndTls()` - RedisClientFactory accepts ACL username, password, TLS
- **Infrastructure:** Direct Redis connection testing (no REST endpoints)

---

### 5. **CatalogueResourceIT.java**
- **Class:** `CatalogueResourceIT`
- **Display Name:** "CatalogueResource Integration Test (Postgres + Redis)"
- **Purpose:** CRUD operations on catalogue (entities, categories, fields)
- **REST Endpoints Tested:**
  - `POST /api/v1/catalogue/entities` - Register entity
  - `GET /api/v1/catalogue/entities` - List entities
  - `GET /api/v1/catalogue/entities/{name}` - Get entity
  - `PUT /api/v1/catalogue/entities/{name}` - Update entity
  - `DELETE /api/v1/catalogue/entities/{name}` - Delete entity
  - `POST /api/v1/catalogue/categories` - Register category
  - `GET /api/v1/catalogue/categories` - List categories
  - `GET /api/v1/catalogue/categories/{name}` - Get category
  - `PUT /api/v1/catalogue/categories/{name}` - Update category
  - `DELETE /api/v1/catalogue/categories/{name}` - Delete category
  - `POST /api/v1/catalogue/fields` - Register field
  - `GET /api/v1/catalogue/fields` - List fields
  - `GET /api/v1/catalogue/fields/{name}` - Get field
  - `DELETE /api/v1/catalogue/fields/{name}` - Delete field
  - `POST /api/v1/catalogue/fields/{name}/assign-category` - Assign field to category
  - `DELETE /api/v1/catalogue/fields/{name}/unassign-category/{category}` - Unassign category
- **Nested Test Classes (6):**
  1. **SetupAndAuth** (3 tests)
     - createTenantAndLogin()
     - unauthenticatedRejected()
     - invalidTokenReturns401()
  2. **EntityCrud** (10 tests)
     - registerUser(), listEntities(), getEntity(), updateEntity()
     - registerPatient(), deletePatient(), getDeletedEntity(), deleteNonExistent()
     - registerBlankName(), upsertEntity()
  3. **CategoryCrud** (7 tests)
     - registerPii(), registerHealth(), listCategories()
     - getCategory(), updateCategory(), deleteCategory(), reregisterCategory()
  4. **FieldAndCategoryAssignment** (6 tests)
     - registerEmail(), registerPhone(), listFields()
     - assignFieldToCategory(), listFieldCategories(), unassignCategory()
  5. **DeletionConstraints** (2 tests)
     - deleteWithAssignedCategory() → 409
     - deleteWithLinkedPolicy() → 409
  6. **OutboxVerification** (5 tests)
     - entityEventsExist(), categoryEventsExist(), fieldEventsExist()
     - streamHasCatalogueEvents()

---

### 6. **GovernanceResourceIT.java**
- **Class:** `GovernanceResourceIT`
- **Display Name:** "GovernanceResource Integration Test (Postgres + Redis)"
- **Purpose:** CRUD operations on governance (purposes, regulations, jurisdictions, policies)
- **REST Endpoints Tested:**
  - `POST /api/v1/governance/purposes` - Register purpose
  - `GET /api/v1/governance/purposes` - List purposes
  - `GET /api/v1/governance/purposes/{name}` - Get purpose
  - `PUT /api/v1/governance/purposes/{name}` - Update purpose
  - `DELETE /api/v1/governance/purposes/{name}` - Delete purpose
  - `POST /api/v1/governance/regulations` - Register regulation
  - `GET /api/v1/governance/regulations` - List regulations
  - Similar for jurisdictions and policies
  - `POST /api/v1/governance/purposes/{purpose}/regulations/{regulation}` - Link purpose to regulation
- **Nested Test Classes (9):**
  1. **SetupAndAuth** (3 tests)
  2. **PurposeCrud** (8 tests)
  3. **RegulationCrud** (8 tests)
  4. **JurisdictionCrud** (6 tests)
  5. **PurposePolicyManagement** (8 tests)
  6. **PurposeRegulationLinking** (6 tests)
  7. **RegulationJurisdictionLinking** (5 tests)
  8. **DeletionConstraints** (3 tests)
  9. **EventStoreVerification** (4 tests)

---

### 7. **ConsentResourceIT.java**
- **Class:** `ConsentResourceIT`
- **Display Name:** "ConsentResource Integration Test (Postgres + Redis)"
- **Purpose:** Consent grant/revoke/evaluate operations and multi-subject isolation
- **REST Endpoints Tested:**
  - `POST /api/v1/subjects/{id}/consent/grant` - Grant consent
  - `POST /api/v1/subjects/{id}/consent/revoke` - Revoke consent
  - `GET /api/v1/subjects/{id}/summary` - Subject summary
  - `GET /api/v1/subjects/{id}/history` - Subject history
  - `GET /api/v1/subjects/{id}/evaluate` - Evaluate consent for scope
- **Nested Test Classes (11):**
  1. **PrerequisitesSetup** - Register tenant, catalogue, governance
  2. **SubjectManagement** - Register/remove subjects
  3. **SingleConsentGrant** - Grant single policy
  4. **SingleConsentRevoke** - Revoke policy
  5. **BatchConsentOps** - Grant/revoke multiple
  6. **SubjectSummaryAndHistory** - Query summary/history
  7. **ConsentExpiration** - Expiring consent
  8. **MultiSubjectIsolation** - Cross-subject isolation
  9. **CrossResourceValidation** - Verify across resources
  10. **DsarVerification** - Verify DSAR export includes consent
  11. **OutboxConsistency** - Outbox and event stream

---

### 8. **ConsentGraphProjectionIT.java**
- **Class:** `ConsentGraphProjectionIT`
- **Display Name:** "ConsentGraphProjection Integration Test (Postgres + Redis)"
- **Purpose:** Tests graph projection of consent relationships
- **REST Endpoints Tested:**
  - Same consent endpoints as ConsentResourceIT
  - Graph query endpoints (to be confirmed from nested classes)
- **Nested Test Classes (7):**
  1. **Setup** - Provision tenant and login
  2. **DataSetup** - Register catalogue and governance
  3. **CatalogueGraphNodes** - Verify catalogue relationships in graph
  4. **GovernanceGraphNodes** - Verify governance relationships
  5. **GrantConsentGraph** - Grant and verify graph updates
  6. **RevokeConsentGraph** - Revoke and verify graph cleanup
  7. **SubjectErasureGraph** - Delete subject and verify graph cleanup

---

### 9. **ProductionStackIT.java**
- **Class:** `ProductionStackIT`
- **Display Name:** "Production Stack Integration Test (Postgres + Redis)"
- **Purpose:** End-to-end integration test of full stack
- **REST Endpoints Tested:** All endpoints (comprehensive test)
- **Nested Test Classes (12):**
  1. **ContainerStartup** - Quarkus startup + CDI wiring
  2. **HealthEndpoints** - /health, /q/health, readiness, liveness
  3. **AuthEnforcement** - Unauthenticated requests rejected
  4. **PlatformLifecycle** - Tenant creation and Keycloak login
  5. **AdminSchemaRegistration** - Admin consent schema setup
  6. **ConsentSingleOps** - Grant, evaluate, revoke
  7. **ConsentBatchOps** - Batch operations
  8. **SubjectData** - Summary and history
  9. **AuthEnforcementWithToken** - Invalid token handling
  10. **RequestValidation** - Invalid request handling
  11. **MultiSubjectIsolation** - Cross-subject isolation
  12. **FinalHealthCheck** - Final health verification

---

### 10. **CreateTenantPipelineIT.java**
- **Class:** `CreateTenantPipelineIT`
- **Display Name:** "Create Tenant Pipeline E2E (Unified Schema + RLS)"
- **Purpose:** Tests tenant creation workflow and CQRS projection
- **REST Endpoints Tested:**
  - `POST /api/v1/platform/iam/users` - Register platform user (creates tenant)
  - GET /health endpoints
- **Nested Test Classes (7):**
  1. **UserRegistrationHttp** - Register user → 201
  2. **EventStoreValidation** - TenantCreated + Billing events exist
  3. **ProjectionValidation** - Tenant row materializes in cveiliam.tenant
  4. **UnifiedSchemaValidation** - All consent tables exist in cveilconsent schema
  5. **TenantStatusValidation** - Tenant status reflects creation
  6. **KeycloakLogin** - Keycloak login succeeds
  7. **PlanValidation** - Subscription row has plan limits

---

### 11. **PlatformIamResourceIT.java**
- **Class:** `PlatformIamResourceIT`
- **Display Name:** "PlatformIamResource Integration Test (Postgres + Redis + Keycloak)"
- **Purpose:** Platform IAM: user registration, lifecycle, RBAC, authentication
- **REST Endpoints Tested:**
  - `POST /api/v1/platform/iam/users` - Register user
  - `GET /api/v1/platform/iam/users` - List users
  - `PUT /api/v1/platform/iam/users/{userId}` - Update user
  - `DELETE /api/v1/platform/iam/users/{userId}` - Disable user
  - `GET /api/v1/platform/iam/users/{userId}/roles` - Get user roles
  - Token endpoint (via Keycloak)
- **Nested Test Classes (7):**
  1. **RealmBootstrap** - Keycloak realm setup
  2. **UserRegistration** - User registration via REST (6 tests)
  3. **UserLifecycle** - Update, disable, enable users
  4. **RoleManagement** - Assign/revoke roles
  5. **ServiceAccounts** - Service account creation
  6. **Authentication** - Token acquisition and usage
  7. **Rbac** - Role-based access control enforcement

---

### 12. **TenantIamResourceIT.java**
- **Class:** `TenantIamResourceIT`
- **Display Name:** "TenantIamResource Integration Test — Unified IAM (Postgres + Redis + Keycloak)"
- **Purpose:** Tenant IAM: user management within tenant scope
- **REST Endpoints Tested:**
  - `POST /api/v1/tenants/{tenantId}/iam/users` - Register tenant user
  - `GET /api/v1/tenants/{tenantId}/iam/users` - List tenant users
  - Similar update/delete for tenant user scope
- **Nested Test Classes (9):**
  1. **Provisioning** - Tenant provisioning via orchestrator
  2. **UserRegistration** - User registration within tenant
  3. **UserLifecycle** - User lifecycle management
  4. **RoleManagement** - Role assignment within tenant
  5. **ServiceAccounts** - Service account creation
  6. **Authentication** - Token acquisition within tenant
  7. **CrossTenantIsolation** - Users isolated per tenant
  8. **Rbac** - Role-based access control
  9. **Cleanup** - Delete test tenants

---

### 13. **RedisTenantAclIsolationIT.java**
- **Class:** `RedisTenantAclIsolationIT`
- **Display Name:** "Redis Tenant ACL Isolation (Cross-Tenant Access Prevention)"
- **Purpose:** Verifies Redis ACL tenant isolation at the infrastructure level
- **Infrastructure:** Direct Redis ACL testing (no REST endpoints)
- **Nested Test Classes (10):**
  1. **Setup** - Create tenants, resolve credentials
  2. **WriteIsolation** - Tenant A can't write to B's namespace
  3. **ReadIsolation** - Tenant A can't read from B's namespace
  4. **DeleteIsolation** - Tenant A can't delete B's keys
  5. **PlatformKeyIsolation** - Tenants can't access ratelimit:* keys
  6. **AdminAccess** - Default user can access all keys
  7. **AclReprovisioning** - Credential rotation
  8. **CommandRestrictions** - @admin/@dangerous commands denied
  9. **EndToEndConsentIsolation** - Consent data isolated via REST
  10. **Cleanup** - Delete test tenants

---

### 14. **CacheInvalidationIT.java**
- **Class:** Not extracted (uses nested classes without main class)
- **Display Name:** Not extracted
- **Purpose:** Tests cache invalidation via pub/sub when catalogue/governance changes
- **Infrastructure:** Redis pub/sub and SSE testing
- **Nested Test Classes (6):**
  1. **Setup** - Create tenant, login, init support
  2. **CatalogueInvalidations** - Entity/field/category changes publish events
  3. **GovernanceInvalidations** - Purpose/regulation changes publish events
  4. **ConsentInvalidations** - Consent grant publishes events
  5. **SseAuth** - SSE endpoint authentication
  6. **SseStreamDelivery** - SSE stream delivers invalidation events
- **REST Endpoints Tested:**
  - All catalogue/governance endpoints (for invalidation)
  - `GET /api/v1/sse/{tenantId}` - Server-sent events endpoint

---

### 15. **GraphResourceIT.java**
- **Class:** Not extracted
- **Display Name:** Not extracted
- **Purpose:** Tests graph query API
- **REST Endpoints Tested:**
  - `POST /api/v1/graph/query` - Graph query endpoint
  - All prerequisite endpoints (catalogue, governance, consent)
- **Nested Test Classes (4):**
  1. **Setup** - Provision tenant, login
  2. **DataSetup** - Register all prerequisites
  3. **GraphQuery** - Execute graph queries
  4. **AuthEnforcement** - Auth on graph endpoint

---

### 16. **SubjectLifecycleIT.java**
- **Class:** Not extracted
- **Display Name:** Not extracted
- **Purpose:** Tests subject lifecycle operations: register, remove, DSAR, shred
- **REST Endpoints Tested:**
  - `POST /api/v1/subjects/{id}` - Register subject
  - `DELETE /api/v1/subjects/{id}` - Remove subject
  - `POST /api/v1/subjects/{id}/dsar-key/shred` - Shred DSAR key
  - All consent endpoints
- **Nested Test Classes (5):**
  1. **Setup** - Provision tenant, login
  2. **Prerequisites** - Register catalogue/governance
  3. **RemoveSubject** - Remove subject endpoint
  4. **ShredDsarKey** - Shred DSAR key
  5. **DsarDeepVerification** - Deep verification of DSAR export

---

### 17. **PlatformLifecycleIT.java**
- **Class:** Not extracted
- **Display Name:** Not extracted
- **Purpose:** Tests platform lifecycle operations: update plan, delete tenant
- **REST Endpoints Tested:**
  - `PATCH /api/v1/platform/tenants/{tenantId}/plan` - Update plan
  - `DELETE /api/v1/platform/tenants/{tenantId}` - Delete tenant
- **Nested Test Classes (3):**
  1. **Setup** - Initialize test support
  2. **UpdatePlan** - Update plan free→pro
  3. **DeleteTenant** - Delete tenant operation

---

## 🗂️ DIRECTORY STRUCTURE

```
cveil-rest-api/src/test/java/com/koostack/cveil/rest/
├── integration/                    # Integration test package
│   ├── CacheInvalidationIT.java
│   ├── CatalogueResourceIT.java
│   ├── ConsentDsarIntegrationIT.java
│   ├── ConsentGraphProjectionIT.java
│   ├── ConsentResourceIT.java
│   ├── CreateTenantPipelineIT.java
│   ├── CveilTestResourceManager.java       # (Utility - not a test)
│   ├── DsarTenantIsolationIT.java
│   ├── GovernanceResourceIT.java
│   ├── GraphResourceIT.java
│   ├── IntegrationTestSupport.java         # (Utility - not a test)
│   ├── PlatformIamResourceIT.java
│   ├── PlatformLifecycleIT.java
│   ├── PostgresSslIntegrationIT.java
│   ├── ProductionStackIT.java
│   ├── RedisTenantAclIsolationIT.java
│   ├── RedisTlsIntegrationIT.java
│   ├── SubjectLifecycleIT.java
│   └── TenantIamResourceIT.java
└── (other test files in parent package)
```

---

## 🔑 KEY TEST PATTERNS & FEATURES

### Annotations Used
- `@QuarkusTest` - Quarkus test framework
- `@QuarkusTestResource(CveilTestResourceManager.class)` - Testcontainers lifecycle
- `@Nested` - Nested test classes (organized by feature)
- `@Test` - Test method
- `@DisplayName("...")` - Human-readable test description
- `@Order(N)` - Enforce test execution order
- `@TestClassOrder(ClassOrderer.OrderAnnotation.class)` - Enforce nested class order
- `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` - Enforce method order
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` - Static state sharing

### Test Verification Layers
Each test verifies:
1. **REST API Response** - Status code, body, headers
2. **Database State** - JDBC queries on Postgres tables
3. **Event Store** - Event aggregate verification
4. **Redis Cache** - Projection cache state, streams, Pub/Sub

### Test Data Management
- Bootstrap key: `test-bootstrap-key-for-integration-tests`
- Tenant IDs generated per test (e.g., `catalogue_it_tenant`)
- Schema isolation via tenant-aware JDBC connections
- Keycloak realm per test scenario

### Libraries & Dependencies
- **REST Assertion:** RestAssured
- **Database:** HikariCP + Postgres JDBC
- **Cache:** Jedis (Redis)
- **IAM:** Keycloak Admin API
- **Containers:** Testcontainers
- **Polling:** Awaitility
- **Assertions:** AssertJ, Hamcrest
- **Server:** Quarkus

---

## 📊 TEST COVERAGE SUMMARY

| Category | Count |
|----------|-------|
| Test Files | 17 |
| Utility/Support Files | 2 |
| Nested Test Classes | ~90+ |
| Test Methods | 250+ |
| REST Endpoints Covered | 80+ |
| Services Tested | Catalogue, Governance, Consent, DSAR, IAM, Platform, Graph |
| Infrastructure Components | Postgres, Redis, Keycloak |

