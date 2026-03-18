# OPERIO - Quick Reference Guide for Planning New Features

## 🎯 CONSENT FLOW AT A GLANCE

```
POST /api/v1/subjects/consent/grant
    ↓
ConsentResource.grant()
    ↓
GrantConsentCommandActuator
    ↓
ConsentGrantedEvent → Event Store (outbox)
    ↓
ConsentAdminProjectionHandler → INSERT consent_ledger
    ↓
ConsentDecisionProjection → UPDATE Redis consent graph
```

**Key Decision Point**: Is consent GRANTED for (subject, policy)?
→ Query Redis graph for latest edge (action="GRANTED" or "REVOKED" or "EXPIRED")

---

## 📊 CONSENT SCHEMA QUICK FACTS

| Table | Purpose | Key Columns |
|-------|---------|------------|
| `consent_ledger` | Audit trail (append-only) | (subject_id, policy_uuid, action, expires_at, created_at) |
| `policy` | Links purpose to field/category | (policy_uuid, purpose_uuid, field_uuid XOR category_uuid) |
| `purpose` | Business processing purpose | (purpose_uuid, purpose_name) |
| `purpose_regulation` | Links purpose to regulation + masking | (on_granted, on_denied, lawful_basis) |
| `regulation` | Legal framework (GDPR, CCPA, etc.) | (regulation_uuid, regulation_name) |
| `jurisdiction` | Geographic scope (EU, CA, TX) | (jurisdiction_uuid, jurisdiction_name) |
| `field` | Data element (email, credit_card) | (field_uuid, field_name, entity_uuid) |
| `entity` | Application/service | (entity_uuid, entity_name) |
| `category` | Data classification (PII, HEALTH) | (category_uuid, category_name) |
| `subject` | Data subject | (subject_uuid, subject_name) |
| `subject_dsar` | Encryption key (digital shredder) | (dsar_id, dsar_key) |
| `dsar_audit_log` | DSAR request tracking | (subject_id, deletion_request_id, status) |

---

## ⚡ DECISION LOGIC (Redis)

**Evaluate Consent**: `POST /api/v1/subjects/consent/evaluate`

```java
for each scope:
  1. HGET field-level policy: FIELD:{entity}/{field}:{purpose}
  2. HGET category-level policy: CAT:{category}:{purpose}
  3. Apply field-over-category precedence
  
result = {granted, denied, reasons, details}
```

**Time Complexity**: O(2n) HGET calls in single Redis pipeline = µs latency

---

## 💰 BILLING SCHEMA QUICK FACTS

| Table | Purpose | Key Columns |
|-------|---------|------------|
| `billing_profile` | Tenant's billing account | (tenant_id, external_customer_id, payment_method_status) |
| `subscription` | Active plan subscription | (tenant_id, plan_tier, status, current_period_end) |
| `daily_usage_rollup` | Metered usage aggregation | (tenant_id, usage_date, metric_name, total_count) |
| `payment_method` | Card/bank account reference | (stripe_payment_method_id, card_brand, card_last4) |
| `invoice` | Generated invoice | (stripe_invoice_id, status, amount_due, period_start/end) |
| `invoice_line_item` | Line-item breakdown | (invoice_id, description, amount, quantity) |
| `stripe_event_log` | Webhook idempotency log | (stripe_event_id, event_type, processed_at) |

---

## 🔗 STRIPE INTEGRATION

**Customer ID**: `external_customer_id` in billing_profile (cus_xxx)
**Subscription ID**: `external_subscription_id` in subscription (sub_xxx)

**Webhook Events Handled**:
- `invoice.created/updated/finalized` → Write invoice + line items
- `invoice.paid` → Mark invoice as paid
- `payment_method.attached/detached` → Sync payment methods
- `customer.subscription.created/updated` → Trigger local commands
- `customer.subscription.deleted` → Mark canceled

**Idempotency**: stripe_event_log table prevents duplicate processing

---

## 📈 USAGE METERING

**Billable Endpoints**: Consent, Governance, Catalogue, Graph
**Meter On**: PAT-authenticated successful (2xx) requests only
**Dimensions**: resource (consent/governance), method (GET/POST), auth_type (pat)

**Pipeline**:
1. BillableMetricFilter counts request
2. MetricsHelper publishes to Redis
3. RecordUsageReportedCommandActuator aggregates daily
4. BillingProjectionHandler writes to daily_usage_rollup
5. Stripe billing system reads for metered billing

---

## 🏛️ GOVERNANCE DATA MODEL

**27 Regulations Pre-Seeded**:
- Federal (8): HIPAA, COPPA, FERPA, GLBA, FCRA, TCPA, CAN-SPAM, ECPA
- State Privacy (19): CCPA, VCDPA, CPA, CTDPA, UCPA, TDPSA, etc.

**17 Categories Pre-Seeded**:
PII, SENSITIVE_PII, HEALTH, FINANCIAL, BIOMETRIC, GENETIC, GEOLOCATION, CHILDREN, EDUCATION, RACIAL_ETHNIC, RELIGIOUS, SEXUAL_ORIENTATION, POLITICAL, COMMUNICATIONS, BEHAVIORAL, PROFESSIONAL, CREDIT

**Masking Types** (per purpose × regulation):
- PLAINTEXT (consent granted)
- REDACT_ALL (consent revoked)
- REDACT_UP_TO_LAST_4 (show last 4 chars)
- HASH256 (SHA-256)
- REDACT_LOCAL (email local part)

---

## 🔐 DIGITAL SHREDDER (DSAR)

**Encryption**:
1. On subject registration → Generate AES-256 key
2. Encrypt key → Store in subject_dsar table
3. On consent event → Shreddable payloads encrypted with key

**Deletion**:
1. Client requests DSAR (Subject Access Request)
2. System creates dsar_audit_log entry
3. Publishes SubjectShreddedEvent
4. Projection handler deletes all consent_ledger entries for subject
5. Purges Redis cache keys via SCAN
6. Delete subject_dsar key

---

## 📡 EVENT TYPES

**Consent Events**:
- `consent.subject.granted` (GrantConsentCommandActuator)
- `consent.subject.revoked` (RevokeConsentCommandActuator)
- `consent.subject.expired` (ExpireConsentCommandActuator)
- `consent.subject.all-revoked` (RevokeAllConsentCommandActuator)

**Subject Events**:
- `consent.subject-entity.registered` (RegisterSubjectCommandActuator)
- `consent.subject-entity.updated` (UpdateSubjectCommandActuator)
- `consent.subject-entity.removed` (RemoveSubjectCommandActuator)

**Governance Events** (registered/updated/removed for each):
- entity, category, field, purpose, regulation, jurisdiction, policy

**Billing Events**:
- `subscription.created` (CreateSubscriptionCommandActuator)
- `subscription.plan_changed` (ChangeSubscriptionPlanCommandActuator)
- `subscription.renewed` (from Stripe webhook)
- `subscription.cancelled` (CancelSubscriptionCommandActuator)
- `usage.reported` (RecordUsageReportedCommandActuator)

---

## 🗂️ PROJECT STRUCTURE

```
operio/
├── cveil-core/                          # Core domain models, events, aggregates
├── cveil-rest-api/                      # JAX-RS endpoints (Consent, Billing, Governance)
├── cveil-web/                           # Frontend dashboard
├── platform/
│   ├── cveil-core-schema/               # plan, tenant tables
│   ├── cveil-billing-schema/            # billing_profile, subscription, invoice
│   ├── cveil-billing-command/           # CreateSubscription, ChangeSubscription, etc.
│   ├── cveil-billing-query-jooq/        # GetSubscription, ListBillingProfiles
│   ├── cveil-billing-projection-jooq/   # BillingProjectionHandler
│   ├── cveil-payment-proc-stripe/       # StripePaymentProcessorService
│   └── cveil-core-command/              # PlatformCommandService
├── tenant/
│   ├── cveil-consent-admin-jooq-common/ # ConsentLedgerRepository, consent schema
│   ├── cveil-consent-admin-command/     # Grant, Revoke, Register, Seed commands
│   ├── cveil-consent-admin-query-jooq/  # Evaluate, GetSubject, ListSubjects
│   ├── cveil-consent-admin-projection-jooq/    # ConsentAdminProjectionHandler
│   ├── cveil-consent-decision-projection-redis/# ConsentDecisionQueryServiceRedis
│   └── cveil-subject-dsar-jooq/         # JooqSubjectDsarService
└── sdks/
    ├── cveil-sdk-java/                  # Java SDK (models, client)
    └── ...other SDKs
```

---

## 🚀 KEY ENTRY POINTS FOR NEW FEATURES

### Adding a Consent-Related Endpoint
1. Add REST method to `ConsentResource`
2. Create `CommandActuator` or `QueryActuator`
3. Define event in `ConsentEventTypes`
4. Update `ConsentAdminProjectionHandler` to handle event
5. Add unit tests using `JooqConsentAdminServiceTest`

### Adding a Billing Feature
1. Create `CommandActuator` in cveil-billing-command
2. Define event in `BillingEventTypes`
3. Update `BillingProjectionHandler`
4. Add Stripe integration (if needed) to `StripePaymentProcessorService`
5. Handle webhook in `WebhookResource`

### Adding Governance Rules
1. Update `SeedConsentGovernanceCommandActuator` with new regulations/categories
2. Create `RegisterRegulationCommandActuator` (if needed)
3. Link via `purpose_regulation` (masking types, lawful basis)

---

## 📚 JOOQ-Generated Classes Location

**Consent Admin Tables**: 
```
tenant/cveil-consent-admin-jooq-common/build/generated-src/jooq/main/
  → com.koostack.cveil.plugin.consent.jooq.generated.tables.*
```

**Billing Tables**:
```
platform/cveil-billing-schema/build/generated-src/jooq/main/
  → com.koostack.cveil.platform.billing.jooq.generated.tables.*
```

**Core Tables**:
```
platform/cveil-core-schema/build/generated-src/jooq/main/
  → com.koostack.cveil.platform.core.jooq.generated.tables.*
```

---

## 🔍 DEBUGGING TIPS

**To trace a consent decision**:
1. Check consent_ledger table for (subject_id, policy_uuid) records
2. Query purpose_regulation for masking rules
3. Check Redis for edge: `subject:{subjectId}:policy:{policyName}`

**To debug usage metering**:
1. Check daily_usage_rollup table for metric_name
2. Verify BillableEndpoints.resolveResource() matches request path
3. Check requestHolder.isAuthenticated() and identity().provider()

**To debug Stripe webhook**:
1. Check stripe_event_log for event_id
2. Verify signature in WebhookResource.handleStripeWebhook()
3. Check billing_profile.external_customer_id matches Stripe

---

## 📝 SQL QUERY TEMPLATES

**Get Latest Consent for Subject**:
```sql
WITH ranked AS (
  SELECT *,
    ROW_NUMBER() OVER (PARTITION BY subject_id, policy_uuid ORDER BY created_at DESC) as rn
  FROM consent_ledger
  WHERE subject_id = ? AND tenant_id = ?
)
SELECT * FROM ranked WHERE rn = 1;
```

**Get Subject's Billing Profile**:
```sql
SELECT * FROM platform_billing.billing_profile
WHERE tenant_id = ?;
```

**Get Active Subscription**:
```sql
SELECT * FROM platform_billing.subscription
WHERE tenant_id = ? AND status IN ('active', 'trialing');
```

**Get Today's Usage**:
```sql
SELECT metric_name, total_count FROM platform_billing.daily_usage_rollup
WHERE tenant_id = ? AND usage_date = CURRENT_DATE;
```

