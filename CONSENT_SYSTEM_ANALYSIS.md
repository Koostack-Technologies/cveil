# Consent Admin Command/Event System Architecture

## 1. JooqConsentAdminCommandService — Full Implementation

**File:** `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/JooqConsentAdminCommandService.java` (27.3 KB)

### Class Overview
- **Implements:** `ConsentAdminCommandService<ConsentAdminCommandConfig>`, `CommandDispatcher`
- **Design:** Event-sourced architecture with command-to-actuator dispatch
- **Registry:** `Map<Class<?>, Actuator<?, ?>> actuators` (ConcurrentHashMap)
- **Event Publishing:** Delegates to `EventService<?>` for publishing domain events

### Command Actuators Registered (36 total)

**Entity Aggregate (3):**
- `RegisterEntityCommandActuator` → `RegisterEntityCommand`
- `UpdateEntityCommandActuator` → `UpdateEntityCommand`
- `RemoveEntityCommandActuator` → `RemoveEntityCommand`

**Category Aggregate (3):**
- `RegisterCategoryCommandActuator` → `RegisterCategoryCommand`
- `UpdateCategoryCommandActuator` → `UpdateCategoryCommand`
- `RemoveCategoryCommandActuator` → `RemoveCategoryCommand`

**Field Aggregate (5):**
- `RegisterFieldCommandActuator` → `RegisterFieldCommand`
- `UpdateFieldCommandActuator` → `UpdateFieldCommand`
- `RemoveFieldCommandActuator` → `RemoveFieldCommand`
- `AssignFieldCategoryCommandActuator` → `AssignFieldCategoryCommand`
- `UnassignFieldCategoryCommandActuator` → `UnassignFieldCategoryCommand`

**Purpose Aggregate (7):**
- `RegisterPurposeCommandActuator` → `RegisterPurposeCommand`
- `UpdatePurposeCommandActuator` → `UpdatePurposeCommand`
- `RemovePurposeCommandActuator` → `RemovePurposeCommand`
- `LinkPurposeToRegulationCommandActuator` → `LinkPurposeToRegulationCommand`
- `UnlinkPurposeFromRegulationCommandActuator` → `UnlinkPurposeFromRegulationCommand`
- `AddPurposePolicyCommandActuator` → `AddPurposePolicyCommand`
- `RemovePurposePolicyCommandActuator` → `RemovePurposePolicyCommand`

**Regulation Aggregate (5):**
- `RegisterRegulationCommandActuator` → `RegisterRegulationCommand`
- `UpdateRegulationCommandActuator` → `UpdateRegulationCommand`
- `RemoveRegulationCommandActuator` → `RemoveRegulationCommand`
- `AddRegulationJurisdictionCommandActuator` → `AddRegulationJurisdictionCommand`
- `RemoveRegulationJurisdictionCommandActuator` → `RemoveRegulationJurisdictionCommand`

**Jurisdiction Aggregate (3):**
- `RegisterJurisdictionCommandActuator` → `RegisterJurisdictionCommand`
- `UpdateJurisdictionCommandActuator` → `UpdateJurisdictionCommand`
- `RemoveJurisdictionCommandActuator` → `RemoveJurisdictionCommand`

**Subject Aggregate (3):**
- `RegisterSubjectCommandActuator` → `RegisterSubjectCommand`
- `UpdateSubjectCommandActuator` → `UpdateSubjectCommand`
- `RemoveSubjectCommandActuator` → `RemoveSubjectCommand`

**SubjectConsent Aggregate (4):**
- `GrantConsentCommandActuator` → `GrantConsentCommand`
- `RevokeConsentCommandActuator` → `RevokeConsentCommand`
- `ExpireConsentCommandActuator` → `ExpireConsentCommand`
- `RevokeAllConsentCommandActuator` → `RevokeAllConsentCommand`

**DSAR/Shredding (2):**
- `DeleteSubjectAuditCommandActuator` → `DeleteSubjectAuditCommand`
- `ShredSubjectCommandActuator` → `ShredSubjectCommand` (optional, conditional)

**Seeding (1):**
- `SeedConsentGovernanceCommandActuator` → `SeedConsentGovernanceCommand`

### Command Dispatch Mechanism

```java
@Override
@SuppressWarnings("unchecked")
public <I, O> O dispatch(I input) {
    Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
    if (actuator == null) {
        throw new IllegalArgumentException(
                "No actuator registered for command type: " + input.getClass().getName());
    }
    return actuator.execute(input);
}
```

### Configuration Method (lines 107–264)

```java
@Override
public void configure(ConsentAdminCommandConfig config) {
    this.eventService = config.eventService();
    
    // 1. Create all actuator instances with eventService
    // 2. Register them in the actuators Map by command record class
    // 3. Special handling: ShredSubjectCommandActuator is conditionally registered
    //    if config.dsarService() != null
    
    // Seeding actuator gets special treatment:
    seedConsentGovernanceActuator = 
        new SeedConsentGovernanceCommandActuator(eventService, this);
    // ↑ Self-reference: seeding dispatches commands through THIS service
}
```

---

## 2. All Command Actuators & Command Record Signatures

**Directory:** `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/`

### Quick Reference Table

| Actuator | Command Record Signature | Purpose |
|----------|--------------------------|---------|
| RegisterEntityCommandActuator | `RegisterEntityCommand(String entityName, String description)` | Register an entity type (e.g., customer, employee) |
| UpdateEntityCommandActuator | `UpdateEntityCommand(String entityName, String description)` | Update entity metadata |
| RemoveEntityCommandActuator | `RemoveEntityCommand(String entityName)` | Remove entity |
| RegisterCategoryCommandActuator | `RegisterCategoryCommand(String categoryName, String description)` | Register a data category (e.g., PII, HEALTH) |
| UpdateCategoryCommandActuator | `UpdateCategoryCommand(String categoryName, String description)` | Update category metadata |
| RemoveCategoryCommandActuator | `RemoveCategoryCommand(String categoryName)` | Remove category |
| RegisterFieldCommandActuator | `RegisterFieldCommand(String fieldName, String entityName, String description, String entityDescription)` | Register a field under an entity |
| UpdateFieldCommandActuator | `UpdateFieldCommand(String fieldName, String entityName, String description)` | Update field metadata |
| RemoveFieldCommandActuator | `RemoveFieldCommand(String fieldName, String entityName)` | Remove field |
| AssignFieldCategoryCommandActuator | `AssignFieldCategoryCommand(String fieldName, String entityName, String categoryName, String categoryDescription)` | Link field to category |
| UnassignFieldCategoryCommandActuator | `UnassignFieldCategoryCommand(String fieldName, String entityName, String categoryName)` | Unlink field from category |
| RegisterPurposeCommandActuator | `RegisterPurposeCommand(String purposeName, String description)` | Register a processing purpose |
| UpdatePurposeCommandActuator | `UpdatePurposeCommand(String purposeName, String description)` | Update purpose metadata |
| RemovePurposeCommandActuator | `RemovePurposeCommand(String purposeName)` | Remove purpose |
| LinkPurposeToRegulationCommandActuator | `LinkPurposeToRegulationCommand(String purposeName, String regulationName, String lawfulBasis, String onGranted, String onDenied)` | Link purpose to regulation with masking policies |
| UnlinkPurposeFromRegulationCommandActuator | `UnlinkPurposeFromRegulationCommand(String purposeName, String regulationName)` | Unlink purpose from regulation |
| AddPurposePolicyCommandActuator | `AddPurposePolicyCommand(String purposeName, String policyType, String fieldName, String entityName, String categoryName)` | Add field or category policy to purpose |
| RemovePurposePolicyCommandActuator | `RemovePurposePolicyCommand(String purposeName, String policyType, String fieldName, String entityName, String categoryName)` | Remove field or category policy |
| RegisterRegulationCommandActuator | `RegisterRegulationCommand(String regulationName, String description)` | Register a regulation (e.g., CCPA, HIPAA) |
| UpdateRegulationCommandActuator | `UpdateRegulationCommand(String regulationName, String description)` | Update regulation metadata |
| RemoveRegulationCommandActuator | `RemoveRegulationCommand(String regulationName)` | Remove regulation |
| AddRegulationJurisdictionCommandActuator | `AddRegulationJurisdictionCommand(String regulationName, String jurisdictionName, String description)` | Link regulation to jurisdiction |
| RemoveRegulationJurisdictionCommandActuator | `RemoveRegulationJurisdictionCommand(String regulationName, String jurisdictionName)` | Unlink regulation from jurisdiction |
| RegisterJurisdictionCommandActuator | `RegisterJurisdictionCommand(String jurisdictionName, String description)` | Register a jurisdiction (e.g., CALIFORNIA, US_FEDERAL) |
| UpdateJurisdictionCommandActuator | `UpdateJurisdictionCommand(String jurisdictionName, String description)` | Update jurisdiction metadata |
| RemoveJurisdictionCommandActuator | `RemoveJurisdictionCommand(String jurisdictionName)` | Remove jurisdiction |
| RegisterSubjectCommandActuator | `RegisterSubjectCommand(String subjectName, String description)` | Register a data subject (person/entity) |
| UpdateSubjectCommandActuator | `UpdateSubjectCommand(String subjectName, String description)` | Update subject metadata |
| RemoveSubjectCommandActuator | `RemoveSubjectCommand(String subjectName)` | Remove subject |
| GrantConsentCommandActuator | `GrantConsentCommand(String subjectId, UUID policyId, String purposeName, String policyType, String fieldName, String entityName, String categoryName, String actor, String ipAddress, String userAgent, Instant expiresAt)` | Grant consent for a policy |
| RevokeConsentCommandActuator | `RevokeConsentCommand(String subjectId, UUID policyId, String purposeName, String policyType, String fieldName, String entityName, String categoryName, String actor, String ipAddress, String userAgent)` | Revoke consent |
| ExpireConsentCommandActuator | `ExpireConsentCommand(String subjectId, UUID policyId, String purposeName, String policyType, String fieldName, String entityName, String categoryName, String actor, String ipAddress, String userAgent)` | Expire consent |
| RevokeAllConsentCommandActuator | `RevokeAllConsentCommand(String subjectId, String actor, String ipAddress, String userAgent)` | Revoke all consent for subject |
| DeleteSubjectAuditCommandActuator | `DeleteSubjectAuditCommand(String subjectId, String deletionRequestId, String actor, String ipAddress, String userAgent)` | Create audit log for deletion request |
| ShredSubjectCommandActuator | `ShredSubjectCommand(String subjectName)` | Cryptographically shred subject data (DSAR) |
| SeedConsentGovernanceCommandActuator | `SeedConsentGovernanceCommand(String tenantId)` | Seed default US consent governance |

---

## 3. SeedConsentGovernanceCommandActuator — Full Implementation Details

**File:** `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/SeedConsentGovernanceCommandActuator.java` (51.9 KB)

### Overview
Registers 94 governance artifacts across 3 phases:
- **Phase 1:** Regulations, jurisdictions, categories, purposes, entities (standalone)
- **Phase 2:** Regulation-jurisdiction links, purpose-regulation links (with masking types), fields
- **Phase 3:** Field-category assignments, purpose-category policies

### seedRegulations() — 27 Total Regulations

**Federal (8):**
1. `HIPAA` — Healthcare privacy
2. `COPPA` — Children's privacy
3. `FERPA` — Student education records
4. `GLBA` — Financial institutions
5. `FCRA` — Credit reporting
6. `TCPA` — Telemarketing
7. `CAN_SPAM` — Email marketing
8. `ECPA` — Electronic communications

**State Comprehensive Privacy Laws (19):**
- `CCPA` (California) — Strictest
- `VCDPA` (Virginia)
- `CPA` (Colorado)
- `CTDPA` (Connecticut)
- `UCPA` (Utah)
- `TDPSA` (Texas)
- `FDBR` (Florida)
- `ICDPA` (Indiana)
- `IowaCDPA` (Iowa) — Narrower scope
- `TIPA` (Tennessee)
- `MCDPA` (Montana)
- `OCPA` (Oregon)
- `DPDPA` (Delaware)
- `NJDPA` (New Jersey)
- `NHPA` (New Hampshire)
- `KCDPA` (Kentucky)
- `NDPA` (Nebraska)
- `MODPA` (Maryland) — Stricter data minimization
- `MNCDPA` (Minnesota)

**Dispatch:** Each regulation created via `RegisterRegulationCommand(regName, description)` through dispatcher

### seedJurisdictions() — 20 Total Jurisdictions

**Federal:**
1. `US_FEDERAL` — Applies nationwide

**State (19):**
- `CALIFORNIA`, `VIRGINIA`, `COLORADO`, `CONNECTICUT`, `UTAH`
- `TEXAS`, `FLORIDA`, `INDIANA`, `IOWA`, `TENNESSEE`
- `MONTANA`, `OREGON`, `DELAWARE`, `NEW_JERSEY`, `NEW_HAMPSHIRE`
- `KENTUCKY`, `NEBRASKA`, `MARYLAND`, `MINNESOTA`

**Dispatch:** Each via `RegisterJurisdictionCommand(jurisdictionName, description)`

### seedRegulationJurisdictionLinks() — Linking Strategy

**Federal regulations → US_FEDERAL (8 links):**
```
HIPAA → US_FEDERAL
COPPA → US_FEDERAL
FERPA → US_FEDERAL
GLBA → US_FEDERAL
FCRA → US_FEDERAL
TCPA → US_FEDERAL
CAN_SPAM → US_FEDERAL
ECPA → US_FEDERAL
```

**State regulations → State jurisdictions (1:1 matching, 19 links):**
```
CCPA → CALIFORNIA
VCDPA → VIRGINIA
CPA → COLORADO
CTDPA → CONNECTICUT
UCPA → UTAH
TDPSA → TEXAS
FDBR → FLORIDA
ICDPA → INDIANA
IowaCDPA → IOWA
TIPA → TENNESSEE
MCDPA → MONTANA
OCPA → OREGON
DPDPA → DELAWARE
NJDPA → NEW_JERSEY
NHPA → NEW_HAMPSHIRE
KCDPA → KENTUCKY
NDPA → NEBRASKA
MODPA → MARYLAND
MNCDPA → MINNESOTA
```

**Total:** 27 links registered via `AddRegulationJurisdictionCommand(regulation, jurisdiction, null)`

### seedPurposeRegulationLinks() — Purpose-Regulation Links with Masking

**16 Core Purposes Created:**
1. SERVICE_DELIVERY
2. MARKETING
3. ANALYTICS
4. PERSONALIZATION
5. TARGETED_ADVERTISING
6. FRAUD_PREVENTION
7. CUSTOMER_SUPPORT
8. RESEARCH
9. THIRD_PARTY_SHARING
10. DATA_SALE
11. PROFILING
12. LEGAL_COMPLIANCE
13. HEALTHCARE_OPS
14. FINANCIAL_SERVICES
15. EMPLOYMENT
16. EDUCATION_OPS

**Link Command Structure:**
```java
LinkPurposeToRegulationCommand(
    String purposeName,
    String regulationName,
    String lawfulBasis,           // e.g., "Consent", "Legal Obligation", "Legitimate Interest"
    String onGranted,             // Masking when consent GRANTED: PLAINTEXT, HASH256, REDACT_*, etc.
    String onDenied               // Masking when consent DENIED: REDACT_ALL, HASH256, REDACT_UP_TO_LAST_4, etc.
)
```

**Key Linking Patterns:**

**HIPAA (5 links):**
- HEALTHCARE_OPS → HIPAA (Lawful: TPO, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- RESEARCH → HIPAA (Lawful: Authorization, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- MARKETING → HIPAA (Lawful: Authorization, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- ANALYTICS → HIPAA (Lawful: De-identification, onGranted: PLAINTEXT, onDenied: HASH256)
- THIRD_PARTY_SHARING → HIPAA (Lawful: Authorization, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- LEGAL_COMPLIANCE → HIPAA (Lawful: Legal Obligation, onGranted: PLAINTEXT, onDenied: PLAINTEXT)

**COPPA (6 purposes):**
- SERVICE_DELIVERY, MARKETING, ANALYTICS, TARGETED_ADVERTISING, THIRD_PARTY_SHARING, PERSONALIZATION
- All with lawful basis "Verifiable Parental Consent", onDenied: REDACT_ALL

**FERPA (4 links):**
- EDUCATION_OPS → FERPA (School Official Exception, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- RESEARCH → FERPA (Written Consent, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- THIRD_PARTY_SHARING → FERPA (Written Consent, onGranted: PLAINTEXT, onDenied: REDACT_ALL)
- LEGAL_COMPLIANCE → FERPA (Legal Obligation, onGranted: PLAINTEXT, onDenied: PLAINTEXT)

**State Comprehensive Privacy Laws (16 standard states + 3 exceptions):**

Standard pattern (shared by VCDPA, CPA, CTDPA, UCPA, TDPSA, FDBR, ICDPA, TIPA, MCDPA, OCPA, DPDPA, NJDPA, NHPA, KCDPA, NDPA, MNCDPA):
```
SERVICE_DELIVERY → (regulation) with lawful basis "Contractual Necessity", onGranted: PLAINTEXT, onDenied: PLAINTEXT
MARKETING → (regulation) with lawful basis "Consent", onGranted: PLAINTEXT, onDenied: REDACT_ALL
ANALYTICS → (regulation) with lawful basis "Legitimate Interest", onGranted: PLAINTEXT, onDenied: HASH256
[and 8 more purposes...]
```

**IowaCDPA (9 links)** — Narrower scope:
- SERVICE_DELIVERY: Contractual Necessity (PLAINTEXT/PLAINTEXT)
- MARKETING: Legitimate Interest (PLAINTEXT/HASH256)
- ANALYTICS: Legitimate Interest (PLAINTEXT/HASH256)
- TARGETED_ADVERTISING: Consent (PLAINTEXT/REDACT_ALL)
- FRAUD_PREVENTION: Legitimate Interest (PLAINTEXT/PLAINTEXT)
- CUSTOMER_SUPPORT: Contractual Necessity (PLAINTEXT/PLAINTEXT)
- THIRD_PARTY_SHARING: Consent (PLAINTEXT/REDACT_ALL)
- DATA_SALE: Consent (PLAINTEXT/REDACT_ALL)
- LEGAL_COMPLIANCE: Legal Obligation (PLAINTEXT/PLAINTEXT)

**MODPA (Maryland, 11 links)** — Stricter data minimization:
- SERVICE_DELIVERY: Contractual Necessity (PLAINTEXT/PLAINTEXT)
- MARKETING: Consent (PLAINTEXT/REDACT_ALL)
- ANALYTICS: Consent (PLAINTEXT/REDACT_ALL)
- PERSONALIZATION: Consent (PLAINTEXT/REDACT_ALL)
- TARGETED_ADVERTISING: Consent (PLAINTEXT/REDACT_ALL)
- FRAUD_PREVENTION: Legitimate Interest (PLAINTEXT/PLAINTEXT)
- CUSTOMER_SUPPORT: Contractual Necessity (PLAINTEXT/PLAINTEXT)
- RESEARCH: Consent (PLAINTEXT/REDACT_ALL)
- THIRD_PARTY_SHARING: Consent (PLAINTEXT/REDACT_ALL)
- DATA_SALE: Consent (PLAINTEXT/REDACT_ALL)
- PROFILING: Consent (PLAINTEXT/REDACT_ALL)
- LEGAL_COMPLIANCE: Legal Obligation (PLAINTEXT/PLAINTEXT)

**CCPA Override:**
- ANALYTICS → CCPA (Consent, PLAINTEXT/REDACT_ALL) — overrides default "Legitimate Interest"

### Categories — 17 Total

Seeded in `seedCategories()`:
1. PII
2. SENSITIVE_PII
3. HEALTH
4. FINANCIAL
5. BIOMETRIC
6. GENETIC
7. GEOLOCATION
8. CHILDREN
9. EDUCATION
10. RACIAL_ETHNIC
11. RELIGIOUS
12. SEXUAL_ORIENTATION
13. POLITICAL
14. COMMUNICATIONS
15. BEHAVIORAL
16. PROFESSIONAL
17. CREDIT

### Fields — ~30 Total

Registered under implicit `__default__` or null entity, with categories assigned:

**PII Fields:** email, phone_number, full_name, first_name, last_name, date_of_birth, mailing_address
**Government ID:** ssn, passport_number, drivers_license, tax_id
**Financial:** credit_card_number, bank_account_number, routing_number
**Online Identifiers:** ip_address, device_id, cookie_id, browser_fingerprint
**PHI (Health):** diagnosis, medication, treatment_record, insurance_id
**Sensitive:** race, ethnicity, religion, sexual_orientation
**Employment:** salary, job_title, employee_id
**Geolocation:** geolocation, zip_code
**Biometric:** biometric_template, facial_scan, fingerprint
**Education:** student_id, gpa, transcript

### Entities — 6 Total

1. `customer` — A customer or client
2. `employee` — An employee or staff member
3. `user` — An application user
4. `patient` — A healthcare patient
5. `prospect` — A prospective customer or lead
6. `vendor` — A third-party vendor

### Field-Category Assignments — ~50 Total

**Strategy:** Multiple assignments per field allowed (e.g., `credit_card_number` → FINANCIAL and SENSITIVE_PII)

Example mappings:
```
email → PII
ssn → SENSITIVE_PII
credit_card_number → FINANCIAL, SENSITIVE_PII
diagnosis → HEALTH
race → SENSITIVE_PII, RACIAL_ETHNIC
ip_address → BEHAVIORAL
[...50 total assignments...]
```

### Purpose-Category Policies — ~46 Total

**Structure:** `AddPurposePolicyCommand(purposeName, "CATEGORY", null, null, categoryName)`

**Key Links:**
```
MARKETING → PII, BEHAVIORAL, GEOLOCATION, COMMUNICATIONS
ANALYTICS → BEHAVIORAL, GEOLOCATION
PERSONALIZATION → PII, BEHAVIORAL
SERVICE_DELIVERY → PII, FINANCIAL, COMMUNICATIONS
TARGETED_ADVERTISING → PII, BEHAVIORAL, GEOLOCATION
FRAUD_PREVENTION → PII, FINANCIAL, GEOLOCATION, BIOMETRIC
CUSTOMER_SUPPORT → PII, FINANCIAL, COMMUNICATIONS
LEGAL_COMPLIANCE → PII, SENSITIVE_PII, FINANCIAL, HEALTH, EDUCATION, BIOMETRIC, GENETIC, GEOLOCATION, BEHAVIORAL, COMMUNICATIONS, CHILDREN, CREDIT, PROFESSIONAL
RESEARCH → PII, HEALTH, BEHAVIORAL, EDUCATION
THIRD_PARTY_SHARING → PII, BEHAVIORAL, GEOLOCATION
DATA_SALE → PII, BEHAVIORAL, GEOLOCATION
PROFILING → PII, FINANCIAL, BEHAVIORAL, CREDIT
HEALTHCARE_OPS → PII, HEALTH, SENSITIVE_PII, BIOMETRIC, FINANCIAL
FINANCIAL_SERVICES → PII, FINANCIAL, CREDIT, SENSITIVE_PII
EMPLOYMENT → PII, PROFESSIONAL, FINANCIAL, SENSITIVE_PII
EDUCATION_OPS → PII, EDUCATION, CHILDREN
```

### Execution Flow

```java
@Override
public Void execute(SeedConsentGovernanceCommand command) {
    String tenantId = command.tenantId();
    
    TenantContext.runWhere(tenantId, () -> {
        // Phase 1: Seed standalone entities (no cross-references needed)
        seedCategories();      // 17 categories
        seedRegulations();     // 27 regulations
        seedJurisdictions();   // 20 jurisdictions
        seedPurposes();        // 16 purposes
        seedEntities();        // 6 entities
        
        awaitProjections(5);   // Wait 5s for events to project
        
        // Phase 2: Seed first-order links (depend on Phase 1)
        seedRegulationJurisdictionLinks();  // 27 links
        seedPurposeRegulationLinks();       // ~100+ links
        seedFields();                       // ~30 fields
        
        awaitProjections(5);
        
        // Phase 3: Seed second-order assignments/policies (depend on Phase 2)
        seedFieldCategoryAssignments();  // ~50 assignments
        seedPurposeCategoryPolicies();   // ~46 policies
    });
}
```

**Safe Dispatch:** All commands wrapped in `dispatchSafe()` which catches and logs duplicate exceptions.

---

## 4. Database Schema — Unified Consent Schema

**File:** `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-jooq-common/src/main/resources/db/migration/consent/V1__create_unified_consent_schema.sql` (257 lines)

### Schema Overview

**Multi-tenant:** Every table includes `tenant_id VARCHAR(255)` for Postgres RLS isolation

**Domain Model:**
- All tables carry BOTH a BIGSERIAL internal PK and a UUID domain key
- Foreign keys reference UUID domain keys (projections can write deterministically)
- Unique constraints on (tenant_id, domain_key)

### Table Structure

#### 1. Field Aggregate
```sql
CREATE TABLE entity (
    entity_id BIGINT PRIMARY KEY,
    entity_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    entity_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),
    UNIQUE (tenant_id, entity_name)
);

CREATE TABLE category (
    category_id BIGINT PRIMARY KEY,
    category_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    category_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    [timestamps and audit columns],
    UNIQUE (tenant_id, category_name)
);

CREATE TABLE field (
    field_id BIGINT PRIMARY KEY,
    field_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    entity_uuid UUID REFERENCES entity(entity_uuid),
    [timestamps and audit columns],
    UNIQUE (tenant_id, field_name, entity_uuid)
);

CREATE TABLE field_category (
    field_category_id BIGINT PRIMARY KEY,
    field_category_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    field_uuid UUID NOT NULL REFERENCES field(field_uuid),
    category_uuid UUID NOT NULL REFERENCES category(category_uuid),
    [timestamps and audit columns],
    UNIQUE (tenant_id, field_uuid, category_uuid)
);
```

#### 2. Regulation Aggregate
```sql
CREATE TABLE regulation (
    regulation_id BIGINT PRIMARY KEY,
    regulation_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    regulation_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    [timestamps and audit columns],
    UNIQUE (tenant_id, regulation_name)
);

CREATE TABLE jurisdiction (
    jurisdiction_id BIGINT PRIMARY KEY,
    jurisdiction_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    jurisdiction_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    [timestamps and audit columns],
    UNIQUE (tenant_id, jurisdiction_name)
);

CREATE TABLE regulation_jurisdiction (
    regulation_jurisdiction_id BIGINT PRIMARY KEY,
    regulation_jurisdiction_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    regulation_uuid UUID NOT NULL REFERENCES regulation(regulation_uuid),
    jurisdiction_uuid UUID NOT NULL REFERENCES jurisdiction(jurisdiction_uuid),
    [timestamps and audit columns],
    UNIQUE (tenant_id, regulation_uuid, jurisdiction_uuid)
);
```

#### 3. Purpose Aggregate
```sql
CREATE TABLE purpose (
    purpose_id BIGINT PRIMARY KEY,
    purpose_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    purpose_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    [timestamps and audit columns],
    UNIQUE (tenant_id, purpose_name)
);

CREATE TABLE purpose_regulation (
    purpose_regulation_id BIGINT PRIMARY KEY,
    purpose_regulation_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    purpose_uuid UUID NOT NULL REFERENCES purpose(purpose_uuid),
    regulation_uuid UUID NOT NULL REFERENCES regulation(regulation_uuid),
    on_granted masking_type NOT NULL DEFAULT 'PLAINTEXT',    -- ← Masking type
    on_denied masking_type NOT NULL DEFAULT 'REDACT_ALL',    -- ← Masking type
    lawful_basis VARCHAR(255),                                 -- ← Lawful basis
    [timestamps and audit columns],
    UNIQUE (tenant_id, purpose_uuid, regulation_uuid)
);

CREATE TABLE policy (
    policy_id BIGINT PRIMARY KEY,
    policy_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    purpose_uuid UUID NOT NULL REFERENCES purpose(purpose_uuid),
    field_uuid UUID REFERENCES field(field_uuid),
    category_uuid UUID REFERENCES category(category_uuid),
    policy_type policy_type NOT NULL,
    [timestamps and audit columns],
    CONSTRAINT ck_policy_target_xor CHECK (
        (field_uuid IS NOT NULL AND category_uuid IS NULL) OR
        (field_uuid IS NULL AND category_uuid IS NOT NULL)
    ),
    UNIQUE (tenant_id, purpose_uuid, field_uuid),
    UNIQUE (tenant_id, purpose_uuid, category_uuid)
);
```

#### 4. Subject Aggregate
```sql
CREATE TABLE subject (
    subject_id BIGINT PRIMARY KEY,
    subject_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    subject_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    [timestamps and audit columns],
    UNIQUE (tenant_id, subject_name)
);
```

#### 5. SubjectConsent Aggregate (Append-Only Ledger)
```sql
CREATE TABLE consent_ledger (
    consent_ledger_id BIGINT PRIMARY KEY,
    consent_ledger_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    policy_uuid UUID NOT NULL REFERENCES policy(policy_uuid),
    subject_id VARCHAR(255) NOT NULL,
    action consent_action NOT NULL,  -- ENUM: GRANT, REVOKE, EXPIRE
    actor VARCHAR(255),
    ip_address VARCHAR(45),
    user_agent VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject(tenant_id, subject_name)
);
```

#### 6. DSAR Aggregate
```sql
CREATE TABLE dsar_audit_log (
    dsar_id BIGINT PRIMARY KEY,
    dsar_audit_uuid UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    deletion_request_id VARCHAR(36) NOT NULL,
    policies_deleted INT NOT NULL DEFAULT 0,
    cached_keys_deleted INT NOT NULL DEFAULT 0,
    audit_entries_deleted INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    actor VARCHAR(255),
    ip_address VARCHAR(45),
    user_agent VARCHAR(1000),
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, deletion_request_id)
);

CREATE TABLE subject_dsar (
    subject_dsar_id BIGINT PRIMARY KEY,
    dsar_id UUID UNIQUE NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    dsar_key VARCHAR(512) NOT NULL,  -- Encryption key for digital shredding
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Enums
```sql
CREATE TYPE masking_type AS ENUM (
    'PLAINTEXT',
    'REDACT_ALL',
    'REDACT_UP_TO_LAST_4',
    'HASH256',
    'REDACT_LOCAL'
);

CREATE TYPE policy_type AS ENUM ('FIELD', 'CATEGORY');

CREATE TYPE consent_action AS ENUM ('GRANT', 'REVOKE', 'EXPIRE');
```

---

## 5. Template/Preset/Industry Concept

**Search Result:** No evidence of existing "template," "preset," or "industry" concept in the codebase.

- Only reference to "template" is for Qute HTML templating in REST API
- No preset regulations, preset jurisdiction sets, or industry-specific configurations exist
- The `SeedConsentGovernanceCommandActuator` is the **only hardcoded seed mechanism** and it seeds a **flat, universal US-based governance model**

### Implications for Parameterized Seeding

The current design **does not support**:
- Multi-industry templates (healthcare, finance, retail, etc.)
- Region-specific presets (EU GDPR, CCPA variants, etc.)
- Customizable initial governance data

**For parameterized seeding, you would need to:**
1. Extract the hardcoded lists (regulations, jurisdictions, purposes, categories, fields) into configurable data structures (JSON, YAML, or database)
2. Create a parametric `SeedConsentGovernanceCommandActuator` that accepts a seed template ID
3. Implement template lookup/loading before Phase 1 execution
4. Map template fields to command actuators dynamically

---

## Summary: Data Model Derivation

**Given jurisdiction IDs, to derive regulations and purposes:**

1. **Regulations from Jurisdiction:**
   - If jurisdiction = "US_FEDERAL" → apply all federal regulations (HIPAA, COPPA, FERPA, GLBA, FCRA, TCPA, CAN_SPAM, ECPA)
   - If jurisdiction = "CALIFORNIA" → apply CCPA
   - If jurisdiction = "VIRGINIA" → apply VCDPA
   - ... (1:1 mapping for other states)

2. **Purposes from Regulations:**
   - Iterate `seedPurposeRegulationLinks()` mapping to find all purposes linked to regulations
   - Filter by regulation names derived in step 1
   - **Example:** If jurisdiction = CALIFORNIA and regulation = CCPA:
     - Purposes: SERVICE_DELIVERY, MARKETING, ANALYTICS (Consent, not Legitimate Interest), PERSONALIZATION, TARGETED_ADVERTISING, FRAUD_PREVENTION, CUSTOMER_SUPPORT, RESEARCH, THIRD_PARTY_SHARING, DATA_SALE, PROFILING, LEGAL_COMPLIANCE

3. **Categories from Purposes:**
   - Look up `seedPurposeCategoryPolicies()` for each derived purpose
   - Collect all categories referenced in policies
   - **Example:** MARKETING → [PII, BEHAVIORAL, GEOLOCATION, COMMUNICATIONS]

4. **Fields:**
   - Seed all ~30 default fields (not jurisdiction-dependent in current design)

5. **Field-Category Assignments:**
   - Seed all ~50 default assignments (not jurisdiction-dependent)

