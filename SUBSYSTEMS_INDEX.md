# 📑 OPERIO Subsystems Documentation - Complete Index

## 🎯 START HERE

You have 3 comprehensive documents totaling **1,425 lines** of detailed technical documentation on OPERIO's consent, billing, and governance subsystems.

---

## 📚 DOCUMENTATION STRUCTURE

### 1️⃣ **OPERIO_COMPREHENSIVE_ANALYSIS.md** (899 lines, 34 KB)
**The Complete Reference Manual**

Contains 8 major sections covering everything:

| Section | Topic | Content |
|---------|-------|---------|
| **1** | Consent Grant/Revoke Flow | REST endpoint → Command → Event → Projection → Database |
| **2** | Subject Management | Registration, data storage, DSAR export, audit trails |
| **3** | Consent Evaluation/Decision | Evaluate endpoint, Redis graph logic, O(1) evaluation |
| **4** | Billing/Subscription Model | Tables, plans (free/pro/enterprise), metering, Stripe |
| **5** | Governance Data Model | Regulations (27), jurisdictions, purposes, masking rules |
| **6** | Data Masking Engine | MaskingStrategy interface, 5 masking types, configuration |
| **7** | Architecture Summary | CQRS, event-driven, multi-tenancy, plugins, RLS |
| **8** | Key Files Summary | File path reference table for all subsystems |

**Best for**: Deep dives, implementation details, troubleshooting, architecture decisions

**Key Sections**:
- 1.1-1.5: Consent ledger schema, ROW_NUMBER queries, event types, no SSE
- 2.1-2.4: Subject DSAR keys, encryption, audit logs
- 3.1-3.3: Redis graph evaluation algorithm, field-over-category precedence
- 4.1-4.5: Billing tables, Stripe webhook handling, subscription lifecycle
- 5.1-5.3: Governance relationships, pre-seeded data (27 regulations, 17 categories)

---

### 2️⃣ **QUICK_REFERENCE_GUIDE.md** (288 lines, 10 KB)
**The Cheat Sheet & Copy-Paste Collection**

Quick lookups with code examples, SQL templates, and decision trees:

| Section | Content |
|---------|---------|
| **Consent Flow** | ASCII flow diagram (REST → Event → Projection) |
| **Schema Quick Facts** | All 10 consent tables in one reference table |
| **Decision Logic** | Redis evaluation pseudocode with precedence rules |
| **Billing Schema** | 7 tables at a glance (billing_profile, subscription, etc.) |
| **Stripe Integration** | Customer ID, subscription ID, webhook events handled |
| **Usage Metering** | Billable endpoints, metric dimensions, aggregation pipeline |
| **Governance Data Model** | 27 regulations, 17 categories, masking types |
| **Digital Shredder** | DSAR flow (encrypt → register → delete → purge) |
| **Event Types** | All consent, subject, billing events in one list |
| **Project Structure** | Directory tree of major modules |
| **Entry Points** | How to add consent features, billing features, governance rules |
| **Debugging Tips** | Tracing consent decisions, metering, Stripe webhooks |
| **SQL Templates** | Copy-paste queries for common operations |

**Best for**: Quick answers, feature planning, copy-paste SQL, debugging

---

### 3️⃣ **EXPLORATION_COMPLETE.md** (238 lines, 10 KB)
**The Navigation & Summary Document**

This document! Contains:
- Summary of findings
- File path reference table
- Schema highlights
- Planning guidelines
- Critical patterns to follow
- Quick links to key code
- Verification checklist

**Best for**: Navigation, planning overview, checking off completed topics

---

## 🗺️ QUICK NAVIGATION

### By Topic

**Consent System**
- REST endpoint: `ConsentResource.java` (file: cveil-rest-api)
- Command: `GrantConsentCommandActuator.java` (file: cveil-consent-admin-command)
- Ledger: `ConsentLedgerRepository.java` (file: cveil-consent-admin-jooq-common)
- Decision: `ConsentDecisionQueryServiceRedis.java` (file: cveil-consent-decision-projection-redis)
- Projection: `ConsentAdminProjectionHandler.java` (file: cveil-consent-admin-projection-jooq)
- Schema: `V1__create_unified_consent_schema.sql`

**Billing System**
- REST endpoint: `BillingResource.java` (file: cveil-rest-api)
- Command: `CreateSubscriptionCommandActuator.java` (file: cveil-billing-command)
- Stripe: `StripePaymentProcessorService.java` (file: cveil-payment-proc-stripe)
- Webhook: `WebhookResource.java` (file: cveil-rest-api)
- Metering: `BillableMetricFilter.java` (file: cveil-rest-api)
- Schema: `V1__platform_billing_create_schema.sql`

**Governance & DSAR**
- Seeding: `SeedConsentGovernanceCommandActuator.java`
- DSAR: `JooqSubjectDsarService.java`
- Subject: `RegisterSubjectCommandActuator.java`

### By Problem

**"How does consent grant work?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 1.1-1.5

**"What's the database schema for consent?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 1.2 OR QUICK_REFERENCE_GUIDE.md "Schema Quick Facts"

**"How do I evaluate consent?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 3 OR QUICK_REFERENCE_GUIDE.md "Decision Logic"

**"How is Stripe integrated?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 4.4 OR QUICK_REFERENCE_GUIDE.md "Stripe Integration"

**"How do I add a new consent feature?"**
→ Read: QUICK_REFERENCE_GUIDE.md "Entry Points for New Features"

**"What are the masking rules?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 5 OR QUICK_REFERENCE_GUIDE.md "Governance Data Model"

**"How does DSAR work?"**
→ Read: OPERIO_COMPREHENSIVE_ANALYSIS.md Section 2.3-2.4 OR QUICK_REFERENCE_GUIDE.md "Digital Shredder"

**"I need SQL to query consent history"**
→ Read: QUICK_REFERENCE_GUIDE.md "SQL Query Templates"

---

## 🔍 KEY DISCOVERIES

### ✅ Architecture Patterns
- **CQRS** with event sourcing (not CRUD)
- **Multi-tenant** via RLS + TenantContext
- **Plugin-based** for swap-able implementations
- **Event-driven** with outbox pattern (eventual consistency)
- **jOOQ** for type-safe SQL (generated from migrations)

### ✅ Consent System
- **Append-only ledger** (consent_ledger table)
- **ROW_NUMBER()** for latest state (DB-agnostic)
- **Redis graph** for O(1) evaluation
- **Field-over-category** precedence
- **27 pre-seeded regulations** (federal + state)
- **17 pre-seeded categories** (PII, HEALTH, FINANCIAL, etc.)

### ✅ Billing System
- **Stripe fully integrated** (customer, subscription, webhook)
- **Idempotent webhooks** via stripe_event_log
- **3-tier plans** with metered limits
- **PAT-based metering** (api_billable_request metric)
- **Daily usage rollup** for reconciliation

### ✅ DSAR/Shredding
- **AES-256 per-subject keys** (subject_dsar table)
- **Deterministic key derivation** (from subject name)
- **Event-driven deletion** (SubjectShreddedEvent)
- **Redis cache purge** (SCAN-based cleanup)
- **Audit trail** (dsar_audit_log table)

### ⚠️ Limitations
- **No native SSE streams** (polling/HTTP only)
- **No real-time push** for consent changes
- **Masking engine** (interface defined, implementation in SDKs)

---

## 📊 DOCUMENT STATISTICS

| Document | Lines | Size | Sections | Tables | Code Examples |
|----------|-------|------|----------|--------|----------------|
| Comprehensive | 899 | 34 KB | 8 | 20+ | 50+ |
| Quick Ref | 288 | 10 KB | 12 | 10 | 30+ |
| Summary | 238 | 10 KB | 8 | 5 | Inline |
| **TOTAL** | **1,425** | **54 KB** | **28** | **35+** | **80+** |

---

## ✨ HOW TO USE THESE DOCS

### For Architecture Understanding
1. Start with EXPLORATION_COMPLETE.md "Architecture Summary"
2. Deep-dive with OPERIO_COMPREHENSIVE_ANALYSIS.md Section 7
3. Reference file paths from EXPLORATION_COMPLETE.md

### For Feature Development
1. Check QUICK_REFERENCE_GUIDE.md "Entry Points for New Features"
2. Find similar code in the project
3. Copy-paste patterns from existing CommandActuators/Projections
4. Verify against OPERIO_COMPREHENSIVE_ANALYSIS.md for details

### For Debugging
1. Trace the flow in QUICK_REFERENCE_GUIDE.md "Debugging Tips"
2. Reference SQL templates for manual checks
3. Check OPERIO_COMPREHENSIVE_ANALYSIS.md for schema details
4. Look up event handlers in OPERIO_COMPREHENSIVE_ANALYSIS.md Section 1.4

### For SQL Queries
1. QUICK_REFERENCE_GUIDE.md has 4 ready-to-use templates
2. OPERIO_COMPREHENSIVE_ANALYSIS.md Section 1.2 has full schema
3. Adapt patterns to your use case

---

## 🎯 COVERAGE CHECKLIST

- ✅ Consent Grant/Revoke Flow (REST → Command → Event → Projection)
- ✅ Consent Ledger (schema, queries, window functions)
- ✅ Event Types (all emitted on consent change)
- ✅ SSE Stream (none; polling/outbox-based)
- ✅ Subject Registration (DSAR key generation)
- ✅ Subject Data Export (DSAR flow)
- ✅ Audit Logging (dsar_audit_log, consent_audit_log)
- ✅ Consent Evaluation (endpoint, Redis algorithm, O(1) performance)
- ✅ Decision Cache (Redis structure, field-over-category)
- ✅ Billing Tables (schema, relationships, migrations)
- ✅ Plan Definition (3 tiers: free, pro, enterprise)
- ✅ Usage Metering (BillableMetricFilter, daily_usage_rollup)
- ✅ Stripe Integration (customer, subscription, webhook)
- ✅ Subscription Lifecycle (events, state machine, renewal)
- ✅ Governance Model (regulations, jurisdictions, purposes)
- ✅ Data Masking (5 types: PLAINTEXT, REDACT_ALL, etc.)

---

## 📞 REFERENCE SHORTCUTS

**Consent Decision Logic**: OPERIO_COMPREHENSIVE_ANALYSIS.md → 3.2 OR QUICK_REFERENCE_GUIDE.md → "Decision Logic"

**Billing Stripe Setup**: OPERIO_COMPREHENSIVE_ANALYSIS.md → 4.4 OR QUICK_REFERENCE_GUIDE.md → "Stripe Integration"

**DSAR Encryption**: OPERIO_COMPREHENSIVE_ANALYSIS.md → 2.1 OR QUICK_REFERENCE_GUIDE.md → "Digital Shredder"

**Governance Categories**: OPERIO_COMPREHENSIVE_ANALYSIS.md → 5.3 (shows seeded data)

**File Locations**: EXPLORATION_COMPLETE.md → "File Path Reference" OR OPERIO_COMPREHENSIVE_ANALYSIS.md → 8

**SQL Queries**: QUICK_REFERENCE_GUIDE.md → "SQL Query Templates"

**Debugging**: QUICK_REFERENCE_GUIDE.md → "Debugging Tips"

---

## 🚀 READY TO START?

1. ✅ Read **EXPLORATION_COMPLETE.md** (5 min overview)
2. ✅ Bookmark **QUICK_REFERENCE_GUIDE.md** (for lookups)
3. ✅ Dive into **OPERIO_COMPREHENSIVE_ANALYSIS.md** (as needed)
4. ✅ Use file paths to find code
5. ✅ Follow patterns from existing components
6. ✅ Test with H2 first, PostgreSQL second

---

**Status**: ✅ Complete  
**Last Updated**: 2025-03-17  
**Coverage**: Consent, Billing, Governance Subsystems  
**Audience**: Platform Engineers, Feature Developers, Architects
