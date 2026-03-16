# Consent Admin Command/Event System — Complete Analysis

**Status:** ✅ Complete (1,356 lines of documentation)  
**Generated:** March 15, 2024

---

## 📚 Documentation Files (Read in This Order)

### 1. **QUICK_REFERENCE.md** — Start Here! (8.2 KB)
Best for: 10-minute overview  
Contains: File pointers, command flow, regulatory mappings, key findings

### 2. **CONSENT_SYSTEM_ANALYSIS.md** — Detailed Reference (30 KB)
Best for: Deep technical understanding  
Contains:
- Full `JooqConsentAdminCommandService` class implementation
- All 36 command actuators with complete signatures
- `SeedConsentGovernanceCommandActuator` full code walkthrough
- Database schema (257 lines of SQL)

### 3. **CONSENT_SEED_DATA_SUMMARY.txt** — Visual Tree (13 KB)
Best for: Understanding governance data hierarchy  
Contains:
- ASCII visualization of 94+ seeded artifacts
- 3-phase execution flow with dependencies
- Jurisdiction → Regulation derivation logic

### 4. **CONSENT_DATA_MODEL_DIAGRAM.txt** — ER Diagrams (18 KB)
Best for: Understanding database relationships  
Contains:
- Entity-relationship diagrams for all 7 aggregates
- Full schema connectivity
- Key design patterns (dual keys, masking types, append-only ledger)

---

## 🎯 Quick Answers

### Q1: What is the command system architecture?
**A:** Event-sourced command/actuator pattern with 36 registered actuators in `JooqConsentAdminCommandService`. Each command dispatches to an actuator which publishes events → projections.

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 1

### Q2: How are commands dispatched?
**A:**
```
dispatch(CommandRecord input)
  → actuators.get(input.getClass())
  → actuator.execute(command)
  → EventService.publish(events)
  → Projections (SQL + Redis)
```

**Location:** See `QUICK_REFERENCE.md` § "Command Dispatch Flow"

### Q3: What are all the command actuators?
**A:** 36 total:
- Entity (3): Register, Update, Remove
- Category (3): Register, Update, Remove
- Field (5): Register, Update, Remove, Assign, Unassign
- Purpose (7): Register, Update, Remove, Link, Unlink, AddPolicy, RemovePolicy
- Regulation (5): Register, Update, Remove, AddJurisdiction, RemoveJurisdiction
- Jurisdiction (3): Register, Update, Remove
- Subject (3): Register, Update, Remove
- SubjectConsent (4): Grant, Revoke, Expire, RevokeAll
- DSAR (2): DeleteSubjectAudit, ShredSubject
- Seeding (1): SeedConsentGovernance

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 2 (Quick Reference Table)

### Q4: What does the seed command create?
**A:** 94+ governance artifacts in 3 phases:
- **Phase 1:** 66 standalone entities (categories, regulations, jurisdictions, purposes, entities)
- **Phase 2:** 157+ links and fields (regulation-jurisdiction, purpose-regulation, fields)
- **Phase 3:** ~96 assignments and policies (field-category, purpose-category)

**Location:** See `CONSENT_SEED_DATA_SUMMARY.txt`

### Q5: How are regulations linked to jurisdictions?
**A:**
- Federal regulations (8) → US_FEDERAL (1:many)
- State regulations (19) → State jurisdictions (1:1 matching)

**Example:**
```
HIPAA → US_FEDERAL
CCPA → CALIFORNIA
VCDPA → VIRGINIA
```

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 3 § seedRegulationJurisdictionLinks()

### Q6: How are purposes linked to regulations with masking?
**A:**
```java
LinkPurposeToRegulationCommand(
    String purposeName,   // e.g., "MARKETING"
    String regulationName, // e.g., "CCPA"
    String lawfulBasis,   // e.g., "Consent"
    String onGranted,     // e.g., "PLAINTEXT"
    String onDenied       // e.g., "REDACT_ALL"
)
```

**Masking Types:** PLAINTEXT, HASH256, REDACT_ALL, REDACT_UP_TO_LAST_4, REDACT_LOCAL

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 3 § seedPurposeRegulationLinks()

### Q7: What is the data model?
**A:** 7 aggregates:
1. **Field:** Entity → Field → Category
2. **Regulation:** Regulation ↔ Jurisdiction
3. **Purpose:** Purpose → Regulation (with masking/lawful basis) + Purpose → Field/Category
4. **Subject:** Subject → ConsensusLedger (append-only)
5. **SubjectConsent:** Ledger entries (GRANT/REVOKE/EXPIRE)
6. **DSAR:** DsarAuditLog + SubjectDsar (encryption key)

All multi-tenant (tenant_id in all tables).

**Location:** See `CONSENT_DATA_MODEL_DIAGRAM.txt`

### Q8: Is there an existing template/preset system?
**A:** **NO.** Zero existing infrastructure. The hardcoded seed model in Java is the only mechanism. To implement parameterized seeding:

1. Extract hardcoded lists → JSON/YAML templates
2. Create template registry
3. Build parametric seed actuator
4. Maintain jurisdiction-regulation mappings

**Location:** See `QUICK_REFERENCE.md` § "No Template/Preset Concept"

### Q9: How does seeding work internally?
**A:** 3 phases with 5-second waits:
```
Phase 1: Register standalone entities (no cross-refs)
  └─ seedCategories(), seedRegulations(), seedJurisdictions(), seedPurposes(), seedEntities()
  └─ Wait 5s for projections

Phase 2: Register first-order links
  └─ seedRegulationJurisdictionLinks(), seedPurposeRegulationLinks(), seedFields()
  └─ Wait 5s for projections

Phase 3: Register second-order assignments/policies
  └─ seedFieldCategoryAssignments(), seedPurposeCategoryPolicies()
```

All dispatched through `CommandDispatcher` (self-reference).

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 3

### Q10: What are the special cases (Iowa, Maryland, California)?
**A:**
- **IowaCDPA:** Narrower scope (9 purposes vs standard 16)
- **MODPA (Maryland):** Stricter data minimization (requires Consent for more purposes)
- **CCPA:** ANALYTICS uses Consent (not standard Legitimate Interest)

**Location:** See `CONSENT_SYSTEM_ANALYSIS.md` § 3 § seedPurposeRegulationLinks()

---

## 🔍 Key Findings Summary

✅ **Architecture**
- 36 command actuators, 1 dispatcher
- Event-sourced with async projections
- Seeding is self-referential (dispatches through service)

✅ **Governance Data**
- 27 regulations (8 federal + 19 state)
- 20 jurisdictions (US_FEDERAL + 19 states)
- 16 purposes, 17 categories, 6 entities, ~30 fields
- 94+ total artifacts in 3-phase seed

✅ **Masking & Lawful Basis**
- 5 masking types: PLAINTEXT, HASH256, REDACT_ALL, REDACT_UP_TO_LAST_4, REDACT_LOCAL
- Lawful basis per purpose-regulation link
- Both on_granted and on_denied masking

✅ **Data Model**
- Multi-tenant (tenant_id everywhere)
- Dual keys (BIGSERIAL + UUID)
- 7 aggregates, append-only ledger
- Cryptographic DSAR support

❌ **No Template System**
- Zero existing "template," "preset," or "industry" infrastructure
- Hardcoded US-based governance only
- All seed data in Java code

---

## 🚀 Next Steps for Parameterized Seeding

1. **Extract seed data** from `SeedConsentGovernanceCommandActuator.java`
2. **Design template registry** (jurisdiction-set → governance config)
3. **Implement parametric seed actuator** (accept template ID)
4. **Maintain derivation mappings** (jurisdiction → regulations → purposes → categories)
5. **Support special cases** (overrides, additional masking types, custom fields)

---

## 📍 Source Code Locations

**Main Service (27.3 KB):**
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  └─ JooqConsentAdminCommandService.java
```

**Seeding Actuator (51.9 KB):**
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  └─ SeedConsentGovernanceCommandActuator.java
```

**All Command Actuators (36):**
```
tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/
  └─ *CommandActuator.java (all 36 files)
```

**Database Schema (257 lines):**
```
tenant/cveil-consent-admin-jooq-common/src/main/resources/db/migration/consent/
  └─ V1__create_unified_consent_schema.sql
```

---

## 📊 Statistics

- **Total documentation:** 1,356 lines
- **Command actuators:** 36
- **Seeded regulations:** 27
- **Seeded jurisdictions:** 20
- **Seeded purposes:** 16
- **Seeded categories:** 17
- **Seeded entities:** 6
- **Seeded fields:** ~30
- **Database tables:** 13 (plus joins)
- **Masking types:** 5
- **Phases in seeding:** 3

---

## 💡 Tips for Using This Documentation

1. **Need quick overview?** → Start with `QUICK_REFERENCE.md`
2. **Need to code the system?** → See `CONSENT_SYSTEM_ANALYSIS.md`
3. **Need to visualize relationships?** → See `CONSENT_DATA_MODEL_DIAGRAM.txt`
4. **Need to understand seed data?** → See `CONSENT_SEED_DATA_SUMMARY.txt`
5. **Need to implement templates?** → Combine all 4 and follow "Next Steps"

---

**All files are in `/Users/mariahgordon/koostacktech/operio/`**

Start with `QUICK_REFERENCE.md` for a 10-minute overview, then dive into the detailed files as needed.

