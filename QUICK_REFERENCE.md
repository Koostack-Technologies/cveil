# Consent Admin Command System — Quick Reference

## Files You Need

1. **CONSENT_SYSTEM_ANALYSIS.md** (30 KB)
   - Full JooqConsentAdminCommandService implementation
   - All 36 command actuators with signatures
   - SeedConsentGovernanceCommandActuator complete implementation (phases 1-3)
   - Database schema SQL

2. **CONSENT_SEED_DATA_SUMMARY.txt** (13 KB)
   - Visual tree of all 94+ seeded artifacts
   - Phase 1: 66 standalone entities
   - Phase 2: 157+ links and fields  
   - Phase 3: ~96 assignments and policies

3. **CONSENT_DATA_MODEL_DIAGRAM.txt** (18 KB)
   - ER diagram of all aggregates
   - Relationship visualization
   - Key design patterns

---

## Key Files in Codebase

### Main Service (27.3 KB)
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  └─ JooqConsentAdminCommandService.java
```
- Registers 36 command actuators
- Implements CommandDispatcher interface
- Dispatch method: `dispatch(input) → actuator.execute()`
- Configure method: Creates all actuator instances with eventService

### Seeding Actuator (51.9 KB)
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  └─ SeedConsentGovernanceCommandActuator.java
```
- Seeds 94+ governance artifacts
- 3 phases with 5-second projection waits
- Full implementation of:
  - `seedRegulations()` — 27 regulations (8 federal + 19 state)
  - `seedJurisdictions()` — 20 jurisdictions (US_FEDERAL + 19 states)
  - `seedRegulationJurisdictionLinks()` — 27 links
  - `seedPurposeRegulationLinks()` — 100+ links with masking & lawful basis

### Database Schema (257 lines)
```
tenant/cveil-consent-admin-jooq-common/src/main/resources/db/migration/consent/
  └─ V1__create_unified_consent_schema.sql
```
- 7 aggregates (Field, Regulation, Purpose, Subject, SubjectConsent, DSAR)
- Multi-tenant (tenant_id in all tables)
- Dual keys (BIGSERIAL + UUID)
- 3 enums: masking_type, policy_type, consent_action

### All Command Actuators (36)
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  ├─ *Entity* (3): Register, Update, Remove
  ├─ *Category* (3): Register, Update, Remove
  ├─ *Field* (5): Register, Update, Remove, Assign, Unassign
  ├─ *Purpose* (7): Register, Update, Remove, Link, Unlink, AddPolicy, RemovePolicy
  ├─ *Regulation* (5): Register, Update, Remove, AddJurisdiction, RemoveJurisdiction
  ├─ *Jurisdiction* (3): Register, Update, Remove
  ├─ *Subject* (3): Register, Update, Remove
  ├─ *SubjectConsent* (4): Grant, Revoke, Expire, RevokeAll
  ├─ *DSAR* (2): DeleteSubjectAudit, ShredSubject
  └─ Seeding (1): SeedConsentGovernance
```

---

## Command Dispatch Flow

```
REST API / Event / Schedule
        ↓
dispatch(CommandRecord)
        ↓
JooqConsentAdminCommandService.dispatch(input)
        ↓
actuators.get(input.getClass()) → Actuator<I, O>
        ↓
actuator.execute(command)
        ↓
TenantContext → Generate events → EventService.publish()
        ↓
Async Outbox (250ms poll)
        ↓
Redis Stream
        ↓
Projection Handler → SQL + Cache Update
```

---

## Regulatory Mapping (Jurisdiction → Regulation)

```
US_FEDERAL:
  - HIPAA, COPPA, FERPA, GLBA, FCRA, TCPA, CAN_SPAM, ECPA

CALIFORNIA:
  - CCPA (strictest)

VIRGINIA:
  - VCDPA

COLORADO, CONNECTICUT, UTAH, TEXAS, FLORIDA, INDIANA, IOWA, TENNESSEE,
MONTANA, OREGON, DELAWARE, NEW_JERSEY, NEW_HAMPSHIRE, KENTUCKY, NEBRASKA, MINNESOTA:
  - 1:1 mapping (CPA, CTDPA, UCPA, TDPSA, FDBR, ICDPA, IowaCDPA, TIPA, MCDPA, OCPA, DPDPA, NJDPA, NHPA, KCDPA, NDPA, MNCDPA)

MARYLAND:
  - MODPA (stricter data minimization)
```

---

## Purpose-Regulation Link Pattern

```java
LinkPurposeToRegulationCommand(
    String purposeName,          // e.g., "MARKETING"
    String regulationName,       // e.g., "CCPA"
    String lawfulBasis,          // e.g., "Consent", "Legal Obligation", "Legitimate Interest"
    String onGranted,            // Masking when consent granted: PLAINTEXT, HASH256, REDACT_*
    String onDenied              // Masking when consent denied: REDACT_ALL, HASH256, etc.
)
```

### Masking Types
- `PLAINTEXT` — No masking
- `HASH256` — Cryptographic hash
- `REDACT_ALL` — Remove entire value
- `REDACT_UP_TO_LAST_4` — Keep last 4 chars (credit cards: XXXX-XXXX-XXXX-1234)
- `REDACT_LOCAL` — Email: hide before @ (XXXX@domain.com)

---

## Data Model Relationships

### Field Aggregate
```
Entity 1:* Field (field_name, entity_uuid)
Field *:* Category (via field_category join)
```

### Regulation Aggregate
```
Regulation *:* Jurisdiction (via regulation_jurisdiction join)
Example: CCPA → CALIFORNIA, HIPAA → US_FEDERAL
```

### Purpose Aggregate
```
Purpose *:* Regulation (via purpose_regulation join + masking + lawful_basis)
Purpose *:* Field (via policy join, policy_type=FIELD)
Purpose *:* Category (via policy join, policy_type=CATEGORY)
```

### Subject Aggregate (Consent Ledger)
```
Subject 1:* ConsensusLedger (append-only history)
ConsentLedger → Policy (policy_uuid FK)
Example action timeline:
  GRANT MARKETING policy (2024-01-15)
  REVOKE MARKETING policy (2024-01-16)
  GRANT ANALYTICS policy (2024-01-17)
```

### DSAR Aggregate (Digital Shredding)
```
Subject 1:1 DsarAuditLog (for deletion request tracking)
Subject 1:1 SubjectDsar (encryption key for shredding)
```

---

## Seed Data Summary

### Artifacts Created (94+)

| Phase | Entity Type | Count | Dependencies |
|-------|------------|-------|--------------|
| 1 | Categories | 17 | None |
| 1 | Regulations | 27 | None |
| 1 | Jurisdictions | 20 | None |
| 1 | Purposes | 16 | None |
| 1 | Entities | 6 | None |
| 2 | Reg↔Juris Links | 27 | Phase 1 |
| 2 | Purpose↔Reg Links | 100+ | Phase 1 |
| 2 | Fields | ~30 | Phase 1 |
| 3 | Field↔Category Assignments | ~50 | Phase 2 |
| 3 | Purpose↔Category Policies | ~46 | Phase 2 |

---

## Configuration & Initialization

```java
@Override
public void configure(ConsentAdminCommandConfig config) {
    this.eventService = config.eventService();
    
    // 1. Instantiate all 36 command actuators
    registerEntityActuator = new RegisterEntityCommandActuator(eventService);
    // ... (33 more)
    
    // 2. Register in actuators map by command record class
    actuators.put(RegisterEntityCommand.class, registerEntityActuator);
    // ... (35 more)
    
    // 3. Seeding actuator (special: self-reference)
    seedConsentGovernanceActuator = new SeedConsentGovernanceCommandActuator(eventService, this);
    actuators.put(SeedConsentGovernanceCommand.class, seedConsentGovernanceActuator);
}
```

---

## No Template/Preset Concept

**Important Finding:** There is NO existing:
- Template system (healthcare, finance, retail industries)
- Preset jurisdiction sets
- Customizable initial governance data
- Industry-specific configurations

The `SeedConsentGovernanceCommandActuator` hardcodes a single **US-based governance model**.

### To Implement Parameterized Seeding:

1. Extract hardcoded lists to JSON/YAML/database
2. Create template registry (e.g., `TemplateId → seed config`)
3. Implement parametric seed actuator accepting template ID
4. Load template before Phase 1, map to command actuators
5. Maintain jurisdiction-regulation derivation mappings

---

## Special Notes

- **Idempotency:** All seed commands are idempotent (duplicate detection wrapped in `dispatchSafe()`)
- **Projection Sync:** 5-second waits between phases allow async event → SQL/Redis propagation
- **Multi-Tenancy:** TenantContext.runWhere(tenantId, ...) isolates all operations
- **Event Sourcing:** All commands publish events → projections materialize read model
- **Masking Strategy:** on_granted and on_denied allow different masking per regulation+purpose
- **CCPA Override:** ANALYTICS uses Consent (not Legitimate Interest) under CCPA
- **IowaCDPA Exception:** Narrower scope (9 purposes, not 16)
- **MODPA Exception:** Stricter data minimization (requires Consent for more purposes)

---

## Files Generated

All analysis documents are at the project root:
```
/Users/mariahgordon/koostacktech/operio/
  ├─ CONSENT_SYSTEM_ANALYSIS.md         (30 KB) ← Detailed implementation
  ├─ CONSENT_SEED_DATA_SUMMARY.txt      (13 KB) ← Visual seed tree
  ├─ CONSENT_DATA_MODEL_DIAGRAM.txt     (18 KB) ← ER diagrams
  └─ QUICK_REFERENCE.md                 (this file)
```

