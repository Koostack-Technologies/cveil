# 🧪 Operio CVEIL REST API - Integration Tests Complete Index

**Generated:** 2024-03-17  
**Location:** `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`

---

## 📑 DOCUMENTATION FILES

This comprehensive inventory consists of **multiple reference documents**, each serving a specific purpose:

### 1. **START HERE: INTEGRATION_TESTS_SUMMARY.md** ⭐
**Purpose:** Comprehensive overview with complete test descriptions  
**Content:**
- Full inventory of all 17 test files
- Detailed description of each test file (purpose, endpoints, test methods)
- REST API endpoints by category
- Verification layers explanation
- Nested test class reference table
- Authentication & authorization patterns

**Best For:** Understanding what tests exist and what they cover

---

### 2. **INTEGRATION_TEST_INVENTORY_QUICK.txt**
**Purpose:** Quick reference formatted for scanning  
**Content:**
- Compact listing of all 17 test files
- Test method counts per file
- Features and endpoints tested
- Nested class breakdown
- Infrastructure & patterns summary
- Estimated test counts

**Best For:** Quick lookup, TL;DR version

---

### 3. **INTEGRATION_TEST_INVENTORY_DETAILED.md**
**Purpose:** Expanded reference with code examples  
**Content:**
- Full markdown documentation
- Test file descriptions with code context
- Nested class hierarchies
- Endpoint lists
- Coverage summary table

**Best For:** Deep diving into specific test areas

---

### 4. **INTEGRATION_TEST_GUIDE.md** (Pre-existing)
**Purpose:** Conceptual guide to integration testing  
**Content:** How integration tests work, best practices

---

### 5. **INTEGRATION_TEST_QUICK_REFERENCE.md** (Pre-existing)
**Purpose:** Quick reference for developers  
**Content:** Endpoint lists, test patterns

---

### 6. **INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md** (Pre-existing)
**Purpose:** Infrastructure configuration details  
**Content:** Container setup, configuration properties

---

### 7. **INTEGRATION_TEST_PATTERNS.md** (Pre-existing)
**Purpose:** Common test patterns and examples  
**Content:** Pattern usage, best practices

---

## 🎯 QUICK FACTS

| Metric | Value |
|--------|-------|
| **Total Test Files** | 17 |
| **Support/Utility Files** | 2 |
| **Estimated Test Methods** | 275+ |
| **Nested Test Classes** | 90+ |
| **REST Endpoints Covered** | 80+ |
| **Lines of Test Code** | 14,200+ |
| **Core Services** | 8 (Catalogue, Governance, Consent, DSAR, Platform IAM, Tenant IAM, Graph, Platform) |
| **Container Types** | 3 (Postgres 16, Redis 7, Keycloak 26.0.8) |
| **Test Framework** | JUnit 5 + Quarkus Test + Testcontainers |

---

## 📂 TEST FILES AT A GLANCE

### Direct Test Methods (No Nested Classes)
1. **ConsentDsarIntegrationIT.java** - 10 DSAR tests
2. **DsarTenantIsolationIT.java** - 3 DSAR isolation tests
3. **PostgresSslIntegrationIT.java** - 7 PostgreSQL SSL tests
4. **RedisTlsIntegrationIT.java** - 5 Redis TLS tests

### Nested Test Classes Structure
5. **CatalogueResourceIT.java** - 6 nested classes, 33 tests
6. **GovernanceResourceIT.java** - 9 nested classes, 51 tests
7. **ConsentResourceIT.java** - 11 nested classes, 55+ tests
8. **ConsentGraphProjectionIT.java** - 7 nested classes, 30+ tests
9. **ProductionStackIT.java** - 12 nested classes, 60+ tests
10. **CreateTenantPipelineIT.java** - 7 nested classes, 21 tests
11. **PlatformIamResourceIT.java** - 7 nested classes, 35 tests
12. **TenantIamResourceIT.java** - 9 nested classes, 40 tests
13. **RedisTenantAclIsolationIT.java** - 10 nested classes, 45 tests
14. **CacheInvalidationIT.java** - 6 nested classes, 30 tests
15. **GraphResourceIT.java** - 4 nested classes, 25 tests
16. **SubjectLifecycleIT.java** - 5 nested classes, 25 tests
17. **PlatformLifecycleIT.java** - 3 nested classes, 13 tests

### Support/Utility Files (NOT Test Classes)
- **CveilTestResourceManager.java** - Testcontainers lifecycle manager
- **IntegrationTestSupport.java** - Shared test utilities and helpers

---

## 🔍 HOW TO USE THESE DOCUMENTS

### "I want to understand what integration tests exist"
**Start with:** `INTEGRATION_TESTS_SUMMARY.md`
- Complete overview of all test files
- Clear descriptions of what each tests
- Organized by tier/feature

### "I need a quick lookup of a specific test"
**Use:** `INTEGRATION_TEST_INVENTORY_QUICK.txt`
- Compact format
- Easy to scan
- Feature summaries

### "I need detailed information about nested test classes"
**Read:** `INTEGRATION_TEST_INVENTORY_DETAILED.md`
- Full nested class hierarchies
- More detailed descriptions
- Endpoint lists

### "I want to add a new test following existing patterns"
**Review:** Previous iteration documents
- `INTEGRATION_TEST_GUIDE.md` - How tests work
- `INTEGRATION_TEST_PATTERNS.md` - Common patterns
- Study existing test files in your area (Catalogue, Consent, etc.)

### "I need to debug a failing test"
**Approach:**
1. Find the test file in this index
2. Locate the specific test method/nested class
3. Check `INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md` for container setup
4. Review `IntegrationTestSupport.java` for test utilities
5. Check `CveilTestResourceManager.java` for environment setup

---

## 🗂️ COMPLETE FILE INVENTORY

### In `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`:

**Active Test Files (17):**
```
CacheInvalidationIT.java ..................... Cache invalidation via Pub/Sub
CatalogueResourceIT.java .................... Catalogue CRUD (entities, categories, fields)
ConsentDsarIntegrationIT.java .............. DSAR compliance (GDPR/CCPA)
ConsentGraphProjectionIT.java .............. Consent graph projection
ConsentResourceIT.java ..................... Consent grant/revoke/evaluate
CreateTenantPipelineIT.java ............... Tenant creation E2E
DsarTenantIsolationIT.java ................. DSAR multi-tenant isolation
GovernanceResourceIT.java .................. Governance CRUD (purposes, regulations, etc.)
GraphResourceIT.java ....................... Graph query API
PlatformIamResourceIT.java ................. Platform IAM (users, roles, auth)
PlatformLifecycleIT.java ................... Platform lifecycle (plan, tenant deletion)
PostgresSslIntegrationIT.java .............. Postgres JDBC SSL configuration
ProductionStackIT.java ..................... End-to-end production stack
RedisTenantAclIsolationIT.java ............ Redis ACL tenant isolation
RedisTlsIntegrationIT.java ................ Redis TLS configuration
SubjectLifecycleIT.java .................... Subject lifecycle (register, remove, DSAR, shred)
TenantIamResourceIT.java ................... Tenant IAM (per-tenant user management)
```

**Support Files (2):**
```
CveilTestResourceManager.java .............. Testcontainers lifecycle (Postgres, Redis, Keycloak)
IntegrationTestSupport.java ............... Shared test utilities (DB, Redis, Keycloak helpers)
```

---

## 🔗 INTERCONNECTIONS

### Test Dependencies & Relationships
```
ProductionStackIT
  ├─ Uses: All services (Catalogue, Governance, Consent, etc.)
  └─ Most comprehensive

CatalogueResourceIT
  ├─ Tests: Entity, Category, Field CRUD
  └─ Used by: ConsentResourceIT (prereq setup), ProductionStackIT

GovernanceResourceIT
  ├─ Tests: Purpose, Regulation, Jurisdiction, Policy CRUD
  └─ Used by: ConsentResourceIT (prereq setup), ProductionStackIT

ConsentResourceIT
  ├─ Depends on: CatalogueResourceIT, GovernanceResourceIT
  ├─ Tests: Consent grant/revoke/evaluate
  └─ Includes: MultiSubjectIsolation, DsarVerification

ConsentDsarIntegrationIT
  ├─ Tests: DSAR export/delete
  └─ Direct tests (no nested classes)

DsarTenantIsolationIT
  ├─ Tests: DSAR cross-tenant isolation
  └─ Related: ConsentDsarIntegrationIT (same endpoints)

RedisTenantAclIsolationIT
  ├─ Tests: Redis ACL isolation at infrastructure level
  └─ Complements: DsarTenantIsolationIT (REST-level isolation)

CreateTenantPipelineIT
  ├─ Tests: User registration → Tenant creation (CQRS)
  └─ Related: PlatformIamResourceIT

PlatformIamResourceIT
  ├─ Tests: Platform-level user, role, auth
  └─ Related: TenantIamResourceIT

TenantIamResourceIT
  ├─ Tests: Tenant-scoped user, role, auth
  └─ Related: PlatformIamResourceIT

ConsentGraphProjectionIT
  ├─ Tests: Graph projection of relationships
  └─ Related: GraphResourceIT

GraphResourceIT
  ├─ Tests: Graph query API
  └─ Uses: Catalogue, Governance, Consent data

CacheInvalidationIT
  ├─ Tests: Pub/Sub invalidation when data changes
  └─ Touches: All CRUD operations (Catalogue, Governance, Consent)

SubjectLifecycleIT
  ├─ Tests: Subject register, remove, DSAR, shred
  └─ Depends on: Catalogue, Governance setup

PlatformLifecycleIT
  ├─ Tests: Plan updates, tenant deletion
  └─ Related: CreateTenantPipelineIT

PostgresSslIntegrationIT
  ├─ Tests: JDBC SSL configuration
  └─ Infrastructure test (no REST endpoints)

RedisTlsIntegrationIT
  ├─ Tests: Redis TLS configuration
  └─ Infrastructure test (no REST endpoints)
```

---

## 📊 COVERAGE BY SERVICE

### Catalogue Service
- **Test File:** CatalogueResourceIT.java (916 lines)
- **Endpoints:** 16 endpoints for entities, categories, fields
- **Nested Classes:** 6
- **Test Methods:** 33
- **Coverage:** Full CRUD, deletion constraints, event verification

### Governance Service
- **Test File:** GovernanceResourceIT.java (1,318 lines)
- **Endpoints:** 20+ endpoints for purposes, regulations, jurisdictions, policies
- **Nested Classes:** 9
- **Test Methods:** 51
- **Coverage:** Full CRUD, linking, deletion constraints, event verification

### Consent Service
- **Test Files:** 
  - ConsentResourceIT.java (1,662 lines) - Core operations
  - ConsentGraphProjectionIT.java (845 lines) - Graph projection
  - ConsentDsarIntegrationIT.java (361 lines) - DSAR export
  - DsarTenantIsolationIT.java (392 lines) - Multi-tenant isolation
- **Endpoints:** 12+ endpoints for grant, revoke, evaluate, summary, history
- **Nested Classes:** 29+
- **Test Methods:** 150+
- **Coverage:** Single/batch operations, isolation, expiration, DSAR, graph, events

### Platform IAM Service
- **Test File:** PlatformIamResourceIT.java (737 lines)
- **Endpoints:** 7+ endpoints for users, roles, service accounts
- **Nested Classes:** 7
- **Test Methods:** 35
- **Coverage:** Registration, lifecycle, roles, RBAC, authentication

### Tenant IAM Service
- **Test File:** TenantIamResourceIT.java (980 lines)
- **Endpoints:** 5+ endpoints for tenant users, roles, service accounts
- **Nested Classes:** 9
- **Test Methods:** 40
- **Coverage:** Registration, lifecycle, roles, RBAC, cross-tenant isolation

### Platform Lifecycle Service
- **Test File:** PlatformLifecycleIT.java (283 lines)
- **Endpoints:** 2 endpoints for plan updates, tenant deletion
- **Nested Classes:** 3
- **Test Methods:** 13
- **Coverage:** Plan updates, tenant deletion, idempotency

### Subject Management Service
- **Test File:** SubjectLifecycleIT.java (690 lines)
- **Endpoints:** 3+ endpoints for subject register, remove, dsar-key/shred
- **Nested Classes:** 5
- **Test Methods:** 25
- **Coverage:** Subject lifecycle, DSAR operations, key management

### Graph API
- **Test File:** GraphResourceIT.java (531 lines)
- **Endpoints:** Graph query endpoint
- **Nested Classes:** 4
- **Test Methods:** 25
- **Coverage:** Graph queries, relationship navigation

### Infrastructure Services
- **PostgreSQL SSL:** PostgresSslIntegrationIT.java (161 lines) - 7 tests
- **Redis TLS:** RedisTlsIntegrationIT.java (174 lines) - 5 tests
- **Redis ACL:** RedisTenantAclIsolationIT.java (1,057 lines) - 45 tests
- **Cache Invalidation:** CacheInvalidationIT.java (691 lines) - 30 tests
- **Tenant Creation:** CreateTenantPipelineIT.java (476 lines) - 21 tests

---

## 🎓 RECOMMENDED READING ORDER

**For New Contributors:**
1. This file (INTEGRATION_TESTS_INDEX.md)
2. INTEGRATION_TESTS_SUMMARY.md (complete overview)
3. INTEGRATION_TEST_INVENTORY_QUICK.txt (quick reference)
4. Existing test files in your feature area
5. INTEGRATION_TEST_GUIDE.md (patterns and best practices)

**For Debugging:**
1. INTEGRATION_TEST_INVENTORY_QUICK.txt (find your test)
2. INTEGRATION_TESTS_SUMMARY.md (understand what it tests)
3. Source test file (examine implementation)
4. IntegrationTestSupport.java (understand helpers)
5. CveilTestResourceManager.java (understand environment)

**For Adding Tests:**
1. INTEGRATION_TEST_PATTERNS.md (review patterns)
2. Similar test file in your feature area (copy structure)
3. INTEGRATION_TEST_GUIDE.md (follow conventions)
4. Submit for review

---

## 📝 NOTES

- All tests use `@QuarkusTest` with `@QuarkusTestResource(CveilTestResourceManager.class)`
- Tests are deterministically ordered using `@Order` annotations
- Tests verify at 3 layers: REST API, Database, Event Store (and sometimes Redis)
- Estimated total test methods: **275+**
- Each test file tests a specific service or feature
- Nested test classes are used for logical grouping and ordering
- Multi-tenant isolation is thoroughly tested across services

---

## 📞 QUICK NAVIGATION

| Need | Reference |
|------|-----------|
| Overview of all tests | INTEGRATION_TESTS_SUMMARY.md |
| Quick lookup | INTEGRATION_TEST_INVENTORY_QUICK.txt |
| Detailed info | INTEGRATION_TEST_INVENTORY_DETAILED.md |
| How to write tests | INTEGRATION_TEST_GUIDE.md |
| Common patterns | INTEGRATION_TEST_PATTERNS.md |
| Infrastructure setup | INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md |
| Endpoint reference | INTEGRATION_TEST_QUICK_REFERENCE.md |
| All files | This file (INTEGRATION_TESTS_INDEX.md) |

---

**Last Updated:** 2024-03-17  
**Comprehensive Inventory Created By:** Exploration Agent
