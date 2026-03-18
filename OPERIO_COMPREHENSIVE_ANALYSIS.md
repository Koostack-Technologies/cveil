# OPERIO CONSENT & BILLING SUBSYSTEMS - COMPREHENSIVE ANALYSIS

## ARCHITECTURE OVERVIEW

The system is built on **event-driven CQRS architecture** with:
- **Event Store**: PostgreSQL-based outbox pattern for multi-tenant
- **Projections**: SQL (admin), Redis (decision graph), jOOQ (billing)
- **Saga Pattern**: Deferred provisioning during tenant creation
- **Digital Shredder Pattern**: AES-256 per-subject encryption keys for DSAR

---

## 1. CONSENT GRANT/REVOKE FLOW

### 1.1 REST Endpoint → Database Flow

**Endpoint**: `/api/v1/subjects/consent/grant`
- **File**: `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/main/java/com/koostack/cveil/rest/resource/ConsentResource.java`
- **Handler**: `ConsentResource.grant()`
- **Auth**: Requires `consent:write` role (enforced by `ApiKeyAuthFilter` + RBAC)

**Command Flow**:
1. Client sends `GrantRequest` (subject, scope, optional expiresAt)
2. `ConsentResource.grant()` resolves policy UUID from scope
3. Dispatches `GrantConsentCommandActuator.GrantConsentCommand`
   - File: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/GrantConsentCommandActuator.java`
4. **Creates event**: `ConsentGrantedEvent`
   - Aggregate ID derived from subject name
   - Aggregate Type: `SubjectConsentAggregate.AGGREGATE_TYPE`
5. **Publishes to Event Store** via `EventService.publishEvent()`
6. Outbox poller picks up event
7. **Projection handler materializes** to `consent_ledger` table
8. **Consent decision projection** (Redis) updates consent graph

**Revoke Flow**: Identical, but publishes `ConsentRevokedEvent`

---

### 1.2 Consent Record Schema (Database)

**File**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-jooq-common/src/main/resources/db/migration/consent/V1__create_unified_consent_schema.sql`

#### **consent_ledger** Table (Append-Only History)
```sql
CREATE TABLE consent_ledger (
    consent_ledger_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consent_ledger_uuid UUID NOT NULL UNIQUE,
    tenant_id           VARCHAR(255) NOT NULL,
    policy_uuid         UUID NOT NULL REFERENCES policy(policy_uuid),
    subject_id          VARCHAR(255) NOT NULL,  -- Subject name, NOT UUID
    action              consent_action NOT NULL, -- GRANT, REVOKE, EXPIRE
    actor               VARCHAR(255),             -- Who performed the action
    ip_address          VARCHAR(45),              -- Client IP
    user_agent          VARCHAR(1000),            -- Browser/SDK info
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          TIMESTAMP,                -- Optional expiration
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject(tenant_id, subject_name)
);

CREATE INDEX idx_ledger_tenant                     ON consent_ledger (tenant_id);
CREATE INDEX idx_ledger_subject                    ON consent_ledger (subject_id);
CREATE INDEX idx_ledger_subject_policy_ts          ON consent_ledger (subject_id, policy_uuid, created_at DESC);
```

**Key Design**:
- **Immutable**: Entries never updated, only inserted or deleted (during DSAR)
- **Denormalized**: `subject_id` is a VARCHAR (not UUID) — allows FK to `subject.subject_name`
- **Multi-key index**: For fast "get latest consent per policy for subject"

#### **policy** Table (Links Purpose + Field/Category)
```sql
CREATE TABLE policy (
    policy_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_uuid   UUID NOT NULL UNIQUE,
    tenant_id     VARCHAR(255) NOT NULL,
    purpose_uuid  UUID NOT NULL REFERENCES purpose(purpose_uuid),
    field_uuid    UUID REFERENCES field(field_uuid),        -- Either field OR category
    category_uuid UUID REFERENCES category(category_uuid),
    policy_type   policy_type NOT NULL,  -- FIELD or CATEGORY
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT ck_policy_target_xor CHECK (
        (field_uuid IS NOT NULL AND category_uuid IS NULL) OR
        (field_uuid IS NULL AND category_uuid IS NOT NULL)
    ),
    UNIQUE (tenant_id, purpose_uuid, field_uuid),
    UNIQUE (tenant_id, purpose_uuid, category_uuid)
);
```

**Related Tables**:
- **entity**: Application/service (e.g., "UserService", "PaymentAPI")
- **field**: Data field in an entity (e.g., "email", "credit_card_number")
- **category**: Data classification (e.g., "PII", "HEALTH", "FINANCIAL")
- **field_category**: N-M mapping (a field can belong to multiple categories)
- **purpose**: Business purpose for processing (e.g., "marketing", "fraud_prevention")
- **purpose_regulation**: Links purpose to regulation with masking types & lawful basis
- **regulation**: Legal framework (e.g., "GDPR", "CCPA", "HIPAA")
- **jurisdiction**: Geographic jurisdiction (e.g., "EU", "California", "Texas")

---

### 1.3 Consent Ledger Implementation

**File**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-jooq-common/src/main/java/com/koostack/cveil/plugin/consent/jooq/ConsentLedgerRepository.java`

**Key Methods**:
- `findAll()` — Returns latest entry per (subject_id, policy_id) using ROW_NUMBER() window function
- `findBySubject(String subjectId)` — Gets all consents for one subject
- `findExpiredGrants(LocalDateTime cutoff)` — For expiration sweeper job

**Query Pattern**:
```java
var rn = DSL.rowNumber()
    .over(DSL.partitionBy(CONSENT_LEDGER.SUBJECT_ID, CONSENT_LEDGER.POLICY_UUID)
          .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
    .as("rn");

var latest = dsl.select(CONSENT_LEDGER.fields())
    .select(rn)
    .from(CONSENT_LEDGER)
    .where(CONSENT_LEDGER.TENANT_ID.eq(tenantId))
    .asTable("ranked");

// SELECT latest + JOIN policy/purpose/field/entity/category
dsl.select(...)
    .from(latest)
    .join(POLICY).on(...)
    .where(latest.field("rn", Integer.class).eq(1))
    .fetch();
```

**Why ROW_NUMBER()?** 
- Works on both PostgreSQL AND H2
- Allows deterministic "latest per group" without CTEs
- Compatible with multi-tenant filtering

---

### 1.4 Events Emitted on Consent Change

**File**: `/Users/mariahgordon/koostacktech/operio/cveil-core/src/main/java/com/koostack/cveil/core/tenant/consent/ConsentEventTypes.java`

**SubjectConsent Events**:
```java
CONSENT_GRANTED = "consent.subject.granted"      // GrantConsentCommandActuator
CONSENT_REVOKED = "consent.subject.revoked"      // RevokeConsentCommandActuator
CONSENT_EXPIRED = "consent.subject.expired"      // ExpireConsentCommandActuator
CONSENT_ALL_REVOKED = "consent.subject.all-revoked"  // RevokeAllConsentCommandActuator
```

**Event Payloads**:
- `ConsentGrantedEvent`: subjectId, policyId, purposeName, policyType, fieldName, entityName, categoryName, actor, ipAddress, userAgent, expiresAt
- `ConsentRevokedEvent`: Same minus expiresAt

**Event Listeners** (subscribed to these event types):
1. **ConsentAdminProjectionHandler** — Writes to `consent_ledger` table
2. **ConsentDecisionQueryServiceRedis** (via projection handler) — Updates Redis consent graph
3. **Metrics Publisher** — Records usage metrics
4. **Audit Logging** (if configured) — External compliance systems

---

### 1.5 SSE Consent Event Stream

⚠️ **No native SSE stream found in codebase**. The system uses:

1. **Polling-based**: Clients call `/api/v1/subjects/{subjectId}/consent` repeatedly
2. **Event Outbox Pattern**: Events written to outbox, polled by background job
3. **Redis Streams** (internal only):
   - ConsentDecisionQueryServiceRedis publishes to Redis streams
   - ShardedStreamConsumer consumes and runs projections
   - NOT exposed to clients directly

**For client-facing real-time updates**, you would implement:
- WebSocket endpoint + event subscription
- Or simple HTTP polling (current pattern)
- Or Server-Sent Events (not currently implemented)

---

## 2. SUBJECT MANAGEMENT

### 2.1 Subject Registration

**Endpoint**: `POST /api/v1/subjects` (implied from `ConsentResource`)

**Command Flow**:
1. `RegisterSubjectCommandActuator.execute()` in `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/RegisterSubjectCommandActuator.java`
2. **Generates DSAR encryption key** (AES-256)
   ```java
   KeyGenerator keyGen = KeyGenerator.getInstance("AES");
   keyGen.init(256);
   byte[] keyBytes = keyGen.generateKey().getEncoded();
   String key = Base64.getEncoder().encodeToString(keyBytes);
   ```
3. Saves key to `subject_dsar` table via `SubjectDsarRepository`
4. Publishes `SubjectRegisteredEvent`
5. Projection handler writes to `subject` table

**subject_dsar Table** (Digital Shredder Pattern):
```sql
CREATE TABLE subject_dsar (
    subject_dsar_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dsar_id          UUID NOT NULL UNIQUE,          -- Deterministic from subject name
    tenant_id        VARCHAR(255) NOT NULL,
    dsar_key         VARCHAR(512) NOT NULL,         -- Base64-encoded AES-256 key
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**subject Table**:
```sql
CREATE TABLE subject (
    subject_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_uuid UUID NOT NULL UNIQUE,
    tenant_id    VARCHAR(255) NOT NULL,
    subject_name VARCHAR(255) NOT NULL,
    description  VARCHAR(1000),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(255),
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255),
    UNIQUE (tenant_id, subject_name)
);
```

---

### 2.2 Subject Data Storage

**Subject model** (from `SubjectView`):
- `uuid`: Unique identifier
- `name`: Human-readable subject identifier
- `description`: Optional metadata
- `createdAt`, `updatedAt`: Audit timestamps
- `createdBy`, `updatedBy`: User who created/modified

**Related Consent State**:
- All consent_ledger entries keyed by (subject_id, policy_uuid)
- Audit trail via dsar_audit_log

---

### 2.3 DSAR Export (Subject Data Export)

**Service**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-subject-dsar-jooq/src/main/java/com/koostack/cveil/plugin/consent/jooq/JooqSubjectDsarService.java`

**Flow**:
1. Client requests export for subject
2. System retrieves DSAR key from `subject_dsar` table
3. Decrypts any shreddable payloads in event store
4. Collects:
   - Subject metadata
   - Complete consent ledger history
   - Audit logs
   - Related governance configurations
5. Returns ZIP or JSON export

**Export DTO**: `SubjectDataExport` in SDK
- Contains full subject consent timeline
- Governance policies that apply
- Masking rules for each jurisdiction

---

### 2.4 Audit Data Captured

**dsar_audit_log Table**:
```sql
CREATE TABLE dsar_audit_log (
    dsar_id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dsar_audit_uuid       UUID NOT NULL UNIQUE,
    tenant_id             VARCHAR(255) NOT NULL,
    subject_id            VARCHAR(255) NOT NULL,
    deletion_request_id   VARCHAR(36) NOT NULL,    -- External DSAR request ID
    policies_deleted      INT NOT NULL DEFAULT 0,  -- Count of policies removed
    cached_keys_deleted   INT NOT NULL DEFAULT 0,  -- Redis keys cleaned
    audit_entries_deleted INT NOT NULL DEFAULT 0,  -- Ledger entries shredded
    status                VARCHAR(20) NOT NULL,    -- 'pending', 'processing', 'completed'
    actor                 VARCHAR(255),             -- Who initiated
    ip_address            VARCHAR(45),
    user_agent            VARCHAR(1000),
    metadata              TEXT,                     -- Custom JSON metadata
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, deletion_request_id)
);
```

**ConsentAuditLog** (in consent_admin schema):
```sql
CREATE TABLE consent_audit_log (
    audit_log_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    audit_log_uuid UUID NOT NULL UNIQUE,
    tenant_id      VARCHAR(255) NOT NULL,
    entity_uuid    UUID,
    action_type    VARCHAR(50) NOT NULL,           -- 'CREATE', 'UPDATE', 'DELETE'
    old_values     TEXT,                            -- JSON before
    new_values     TEXT,                            -- JSON after
    actor          VARCHAR(255),
    ip_address     VARCHAR(45),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## 3. CONSENT EVALUATION/DECISION

### 3.1 Evaluate Endpoint

**Endpoint**: `POST /api/v1/subjects/consent/evaluate` (from ConsentResource)

**DTO**:
```java
record EvaluateRequest(
    String subject,
    Set<ConsentScope> scopes  // {entityName, fieldName, category, purposeName}
)
```

**File**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-query-jooq/src/main/java/com/koostack/cveil/plugin/consent/jooq/EvaluateConsentQueryActuator.java`

```java
public ConsentResponse execute(EvaluateConsentQuery query) {
    return consentService.evaluateConsent(query.subject(), query.scopes());
}
```

---

### 3.2 Decision Logic

**File**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-decision-projection-redis/src/main/java/com/koostack/cveil/tenant/consent/projection/decision/ConsentDecisionQueryServiceRedis.java`

**Implementation**: **REDIS GRAPH** (NOT WASM, pure Java)

**Algorithm**:
```
FOR EACH scope:
  1. Build field-level policy key:
     "FIELD:{entity}/{field}:{purpose}"
  2. Build category-level policy key:
     "CAT:{category}:{purpose}"
  3. Pipeline 2 HGET calls to Redis (one round trip)
  4. Extract "action" property from edge
  
APPLY PRECEDENCE:
  - Field-level > Category-level (field more specific)
  - If no record found → DENIED (default deny)
  - If action == "GRANTED" → add to granted set
  - If action == "REVOKED" or "EXPIRED" → denied set

RETURN:
  ConsentResponse {
    granted: Set<ConsentScope>,
    denied: Set<ConsentScope>,
    reasons: Map<fieldName, reasonString>,
    details: Map<ConsentScope, DecisionDetail>
  }
```

**Redis Graph Structure**:
- **Nodes**:
  - `subject:{subjectId}` → properties: {name, created_at, ...}
  - `policy:{policyName}` → properties: {purpose, field, entity, category}
  - `purpose:{purposeName}` → properties: {description}
  - `field:{entity}/{field}` → properties: {entity, field, type}
  - `category:{categoryName}` → properties: {description}

- **Edges**:
  - `subject --[POLICY]--> policy` → properties: {action, expires_at, granted_at}
  - `policy --[PURPOSE]--> purpose`
  - `policy --[FIELD]--> field`
  - `field --[CATEGORY]--> category`

**Performance**: O(1) field + O(1) category = single Redis pipeline call (µs)

---

### 3.3 Decision Cache (Redis)

**Cache Key Format**:
```
cveil:consent:graph:{tenantId}:{subjectId}
```

**Cache Operations**:
- **HGETs**: Fetch edge properties (action, expiration)
- **SCAN**: Count/delete all keys for a subject (for DSAR cleanup)
- **ZRANGE**: Paginate across node types

**Invalidation**:
1. When consent changes (grant/revoke/expire) → event triggers projection
2. Projection handler updates Redis graph directly
3. On DSAR shred → Delete all keys for subject via `deleteCachedKeysForSubject()`

---

## 4. BILLING/SUBSCRIPTION MODEL

### 4.1 Billing Database Schema

**File**: `/Users/mariahgordon/koostacktech/operio/platform/cveil-billing-schema/src/main/resources/db/migration/platform-billing/V1__platform_billing_create_schema.sql`

#### **billing_profile** Table
```sql
CREATE TABLE platform_billing.billing_profile (
    tenant_id               VARCHAR(255) PRIMARY KEY REFERENCES platform_core.tenant(tenant_id),
    payment_processor       VARCHAR(50) NOT NULL DEFAULT 'stripe',
    external_customer_id    VARCHAR(255) UNIQUE,     -- Stripe Customer ID (cus_xxx)
    billing_email           VARCHAR(255) NOT NULL,
    tax_id                  VARCHAR(50),
    payment_method_status   VARCHAR(50) NOT NULL DEFAULT 'unconfigured'
        CHECK (payment_method_status IN ('valid', 'invalid', 'unconfigured')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### **subscription** Table
```sql
CREATE TABLE platform_billing.subscription (
    subscription_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                VARCHAR(255) NOT NULL REFERENCES platform_core.tenant,
    external_subscription_id VARCHAR(255) UNIQUE,    -- Stripe Subscription ID (sub_xxx)
    plan_tier                VARCHAR(50) NOT NULL,   -- 'free', 'pro', 'enterprise'
    
    -- Plan limits (copied from plan table at subscription time)
    eval_limit_monthly       BIGINT NOT NULL DEFAULT 10000,
    subject_limit            BIGINT NOT NULL DEFAULT 1000,
    burst_limit_per_min      INT NOT NULL DEFAULT 100,
    
    status                   VARCHAR(50) NOT NULL
        CHECK (status IN ('active', 'canceled', 'incomplete', 'past_due', 'trialing')),
    
    -- Billing cycle
    current_period_start     TIMESTAMPTZ NOT NULL,
    current_period_end       TIMESTAMPTZ NOT NULL,
    cancel_at_period_end     BOOLEAN NOT NULL DEFAULT FALSE,
    
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### **daily_usage_rollup** Table (Metering)
```sql
CREATE TABLE platform_billing.daily_usage_rollup (
    tenant_id          VARCHAR(255) NOT NULL REFERENCES platform_core.tenant,
    usage_date         DATE NOT NULL,
    metric_name        VARCHAR(50) NOT NULL,  -- 'consent_evaluations', 'api_requests', ...
    total_count        BIGINT NOT NULL DEFAULT 0,
    synced_to_billing  BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, usage_date, metric_name)
);
```

#### **payment_method** Table (V4 Migration)
```sql
CREATE TABLE platform_billing.payment_method (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               VARCHAR(255) NOT NULL,
    stripe_payment_method_id VARCHAR(255) NOT NULL UNIQUE,
    type                    VARCHAR(50) NOT NULL,           -- 'card', 'bank_account'
    card_brand              VARCHAR(50),                    -- 'visa', 'mastercard'
    card_last4              VARCHAR(4),                     -- Last 4 digits
    card_exp_month          SMALLINT,
    card_exp_year           SMALLINT,
    is_default              BOOLEAN NOT NULL DEFAULT FALSE,
    status                  VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### **invoice** & **invoice_line_item** Tables (V4 Migration)
```sql
CREATE TABLE platform_billing.invoice (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               VARCHAR(255) NOT NULL,
    stripe_invoice_id       VARCHAR(255) NOT NULL UNIQUE,
    stripe_subscription_id  VARCHAR(255),
    status                  VARCHAR(50) NOT NULL,          -- 'draft', 'open', 'paid', 'void'
    amount_due              BIGINT NOT NULL DEFAULT 0,
    amount_paid             BIGINT NOT NULL DEFAULT 0,
    total                   BIGINT NOT NULL DEFAULT 0,
    hosted_invoice_url      TEXT,
    period_start            TIMESTAMPTZ,
    period_end              TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_billing.invoice_line_item (
    id                      UUID PRIMARY KEY,
    invoice_id              UUID NOT NULL REFERENCES platform_billing.invoice(id) ON DELETE CASCADE,
    stripe_line_item_id     VARCHAR(255) NOT NULL,
    description             TEXT,
    amount                  BIGINT NOT NULL DEFAULT 0,
    quantity                INTEGER,
    period_start            TIMESTAMPTZ,
    period_end              TIMESTAMPTZ
);
```

#### **stripe_event_log** Table (V4 Migration - Idempotency)
```sql
CREATE TABLE platform_billing.stripe_event_log (
    stripe_event_id         VARCHAR(255) PRIMARY KEY,
    event_type              VARCHAR(255) NOT NULL,
    processed_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload                 JSONB
);
```

---

### 4.2 Plans Definition

**File**: `/Users/mariahgordon/koostacktech/operio/platform/cveil-core-schema/src/main/resources/db/migration/platform-core/V3__create_plan_table.sql`

```sql
CREATE TABLE platform_core.plan (
    plan_id         VARCHAR(50) PRIMARY KEY,
    display_name    VARCHAR(200) NOT NULL,
    description     TEXT,
    eval_limit      BIGINT NOT NULL DEFAULT 0,           -- Evaluations/month (-1 = unlimited)
    subject_limit   BIGINT NOT NULL DEFAULT 0,           -- Subjects (-1 = unlimited)
    burst_limit     INTEGER NOT NULL DEFAULT 0,          -- Requests/min
    is_public       BOOLEAN NOT NULL DEFAULT TRUE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_core.plan VALUES
    ('free',       'Free',       'Community tier',           1000,    100,   10,  TRUE, TRUE, 0),
    ('pro',        'Pro',        'Professional tier',       50000,   5000,  100, TRUE, TRUE, 1),
    ('enterprise', 'Enterprise', 'Unlimited enterprise',       -1,     -1,   -1, FALSE, TRUE, 2);
```

---

### 4.3 Usage Metering (BillableMetricFilter)

**File**: `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/main/java/com/koostack/cveil/rest/filter/BillableMetricFilter.java`

**Billing Rules**:
- ✅ **Metered**: All PAT-authenticated requests to billable endpoints
- ❌ **NOT metered**: Dashboard/JWT requests, errors (4xx/5xx)

**Billable Endpoints** (from BillableEndpoints):
- `/api/v1/subjects/...` (consent)
- `/api/v1/governance/...`
- `/api/v1/catalogue/...`
- `/api/v1/graph/...`

**Metric Dimensions**:
```java
metrics.counter(
    "api_billable_request",
    tenantId,
    Map.of(
        "resource", "consent",      // consent, governance, etc.
        "method", "POST",           // GET, POST, PUT, DELETE
        "auth_type", "pat"          // Always "pat"
    )
);
```

**Usage Aggregation**:
1. Filter publishes `api_billable_request` counter
2. Metrics service aggregates to Redis
3. Batch job (RecordUsageReportedCommandActuator) rolls up daily
4. Publishes `UsageReportedEvent` to event store
5. Billing projection materializes to `daily_usage_rollup` table

---

### 4.4 Stripe Integration

**Files**:
- **Processor**: `/Users/mariahgordon/koostacktech/operio/platform/cveil-payment-proc-stripe/src/main/java/com/koostack/cveil/plugin/platform/stripe/StripePaymentProcessorService.java`
- **Webhook Handler**: `/Users/mariahgordon/koostacktech/operio/cveil-rest-api/src/main/java/com/koostack/cveil/rest/resource/WebhookResource.java`

#### **Customer Creation**
```java
// In StripePaymentProcessorService
public void createCustomer(String tenantId, String billingEmail, String taxId) {
    CustomerCreateParams params = CustomerCreateParams.builder()
        .setEmail(billingEmail)
        .setDescription("Tenant: " + tenantId)
        .setTaxIdData(List.of(...))
        .build();
    Customer customer = Customer.create(params);
    // Return customer.getId() → "cus_xxx"
}
```

#### **Subscription Creation**
```java
// CreateSubscriptionCommandActuator
public Void execute(CreateSubscriptionCommand command) {
    // 1. Validate plan exists
    PlanInfo plan = planQueryService.getPlan(command.planTier());
    
    // 2. Check no existing subscription
    BillingProfileAggregate existing = eventService.hydrateAggregate(...);
    
    // 3. Publish event
    publishEvent(SubscriptionCreatedEvent(
        tenantId: command.tenantId(),
        planTier: command.planTier(),
        evalLimit: plan.evalLimit(),
        subjectLimit: plan.subjectLimit(),
        externalSubscriptionId: "sub_xxx"  // From Stripe API call
    ));
}
```

#### **Webhook Handling**
```java
@POST
@Path("/webhooks/stripe")
public Response handleStripeWebhook(
    @HeaderParam("Stripe-Signature") String signature,
    String payload
) {
    // 1. Verify signature
    WebhookEvent event = paymentService.verifyAndParseWebhook(payload, signature);
    
    // 2. Idempotency check (stripe_event_log)
    if (billingQueryService.isWebhookEventProcessed(event.externalEventId())) {
        return Response.ok(Map.of("received", true, "duplicate", true)).build();
    }
    
    // 3. Route by event type
    switch(event.eventType()) {
        case "invoice.created", "invoice.updated", "invoice.finalized":
            handleInvoiceEvent(...);
        case "invoice.paid":
            handleInvoicePaid(...);
        case "payment_method.attached":
            handlePaymentMethodAttached(...);
        ...
    }
    
    // 4. Mark processed
    billingQueryService.markWebhookEventProcessed(
        event.externalEventId(),
        event.eventType(),
        event.payload()
    );
}
```

---

### 4.5 Subscription Lifecycle

**State Machine** (BillingProfileAggregate):
```
UNINITIALIZED
    ↓ (CreateSubscriptionCommand)
ACTIVE (subscription exists, current_period_end in future)
    ↓ (ChangeSubscriptionPlanCommand)
PLAN_CHANGE_PENDING (waiting for new subscription from Stripe)
    ↓ (SubscriptionRenewedEvent from webhook)
ACTIVE
    ↓ (CancelSubscriptionCommand)
CANCELED (cancel_at_period_end=TRUE)
    ↓ (current_period_end passes)
EXPIRED
```

**Events**:
- `SubscriptionCreatedEvent` — Initial subscription
- `SubscriptionPlanChangedEvent` — Upgrade/downgrade
- `SubscriptionRenewedEvent` — Period renewal (from webhook)
- `SubscriptionCancelledEvent` — Cancellation initiated
- `UsageReportedEvent` — Metered usage

---

## 5. GOVERNANCE DATA MODEL

### 5.1 Relationships

```
JURISDICTION
├── US Jurisdictions (states: CA, NY, TX...)
├── International (EU, UK...)
└── Federal (US Federal)
        ↑
        │ (regulation_jurisdiction)
        │
    REGULATION
    ├── Federal (GDPR, CCPA, HIPAA, COPPA, FERPA, GLBA, FCRA, TCPA, CAN-SPAM, ECPA)
    ├── State (27 privacy laws: VCDPA, CPA, CTDPA, UCPA, TDPSA, FDBR, etc.)
    └── Industry-specific (HIPAA for health, FERPA for education)
            ↑
            │ (purpose_regulation + lawful_basis + masking_types)
            │
        PURPOSE
        └── Business purposes: marketing, fraud_prevention, consent, service_delivery, analytics
                ↓ (purpose_policy)
        POLICY (XOR: field OR category)
        ├── FIELD → ENTITY / FIELD
        │   ├── entity: "UserService", "PaymentAPI"
        │   └── field: "email", "credit_card", "phone"
        │
        └── CATEGORY
            ├── PII, SENSITIVE_PII, HEALTH, FINANCIAL, BIOMETRIC
            ├── GENETIC, GEOLOCATION, CHILDREN, EDUCATION
            ├── RACIAL_ETHNIC, RELIGIOUS, SEXUAL_ORIENTATION, POLITICAL
            ├── COMMUNICATIONS, BEHAVIORAL, PROFESSIONAL, CREDIT
            └── (17 categories total)

FIELD → FIELD_CATEGORY ← CATEGORY
         (N-M mapping)
```

---

### 5.2 Storage (Tables & Foreign Keys)

**governance Schema Tables**:
```sql
regulation (regulation_id, regulation_uuid, tenant_id, regulation_name, description)
jurisdiction (jurisdiction_id, jurisdiction_uuid, tenant_id, jurisdiction_name)
regulation_jurisdiction (regulation_uuid FK, jurisdiction_uuid FK, unique constraint)

purpose (purpose_id, purpose_uuid, tenant_id, purpose_name)
purpose_regulation (purpose_uuid FK, regulation_uuid FK, on_granted ENUM, on_denied ENUM, lawful_basis)

entity (entity_id, entity_uuid, tenant_id, entity_name)
field (field_id, field_uuid, tenant_id, field_name, entity_uuid FK)
category (category_id, category_uuid, tenant_id, category_name)
field_category (field_uuid FK, category_uuid FK, unique constraint)

policy (policy_id, policy_uuid, tenant_id, purpose_uuid FK, field_uuid FK XOR category_uuid FK)
```

**All PK/FK Relationships Use UUIDs** (not BIGINT) for deterministic, replay-safe projections.

---

### 5.3 Seeding (Industry Presets)

**File**: `/Users/mariahgordon/koostacktech/operio/tenant/cveil-consent-admin-command/src/main/java/com/koostack/cveil/plugin/consent/jooq/SeedConsentGovernanceCommandActuator.java`

**Seeded Data**:
- **Categories**: 17 (PII, HEALTH, FINANCIAL, GENETIC, BIOMETRIC, etc.)
- **Regulations**: 27
  - **Federal** (8): HIPAA, COPPA, FERPA, GLBA, FCRA, TCPA, CAN-SPAM, ECPA
  - **State Privacy Laws** (19): CCPA, VCDPA, CPA, CTDPA, UCPA, TDPSA, FDBR, ICDPA, IowaCDPA, TIPA, MCDPA, OCPA, DPDPA, NJDPA, NHPA, KCDPA, NDPA, (+ more)
- **Jurisdictions**: US States + Federal + International
- **Purposes**: marketing, fraud_prevention, customer_service, analytics, consent_compliance
- **Purpose-Regulation Links**: With masking_type (PLAINTEXT, REDACT_ALL, REDACT_UP_TO_LAST_4, HASH256, REDACT_LOCAL) and lawful_basis

**Seeding Trigger**: 
- Runs during tenant creation (DeferredProvisioningTask)
- Dispatches via consent CommandDispatcher
- All operations are idempotent (duplicate upsert)

---

## 6. DATA MASKING ENGINE

### 6.1 What It Does

**File**: `/Users/mariahgordon/koostacktech/operio/cveil-core/src/main/java/com/koostack/cveil/core/service/MaskingStrategy.java`

```java
public interface MaskingStrategy<T> extends Plugin<T> {
    Object mask(Object obj, Set<ConsentScope> masked);
}
```

**Purpose**: Applies redaction/hashing to data based on:
- Consent status (granted vs. revoked)
- Jurisdiction regulations
- Data classification (category)

**Masking Types** (from purpose_regulation.on_granted / on_denied):
1. **PLAINTEXT** — Return data as-is (consent granted)
2. **REDACT_ALL** — Replace entire value (e.g., "****")
3. **REDACT_UP_TO_LAST_4** — Keep last 4 chars (e.g., "XXX-XX-1234" for SSN)
4. **HASH256** — SHA-256 hash (irreversible)
5. **REDACT_LOCAL** — Redact local part of email (contact@****, keep domain)

---

### 6.2 Implementation Location

**Core Interface**: `/Users/mariahgordon/koostacktech/operio/cveil-core/src/main/java/com/koostack/cveil/core/service/MaskingStrategy.java`

**No concrete implementation found in REST API**. Likely:
- Used by SDKs (Java, Python, Node.js)
- Applied during query response marshaling
- Configured per-purpose via purpose_regulation table

---

### 6.3 Configuration

**Source**: `purpose_regulation` table
```sql
CREATE TABLE purpose_regulation (
    purpose_regulation_id   BIGINT PRIMARY KEY,
    purpose_uuid            UUID FK,
    regulation_uuid         UUID FK,
    on_granted              masking_type NOT NULL DEFAULT 'PLAINTEXT',
    on_denied               masking_type NOT NULL DEFAULT 'REDACT_ALL',
    lawful_basis            VARCHAR(255),
    ...
);
```

**Example**:
```
Purpose: "marketing"
Regulation: "CCPA"
on_granted: PLAINTEXT     (if consented, return full data)
on_denied: REDACT_ALL     (if revoked, return "****")
lawful_basis: "opt-in"
```

---

## 7. ARCHITECTURE SUMMARY

### Event-Driven Flow

```
Client
    ↓ (REST API)
ConsentResource / BillingResource
    ↓ (Dispatch)
CommandActuator (GrantConsent, CreateSubscription, etc.)
    ↓ (Publish)
EventService → PostgreSQL Outbox
    ↓ (Polling)
OutboxPoller (250ms interval)
    ↓ (Publish)
Redis Streams / Event Store
    ↓ (Subscribe)
ProjectionHandlers (concurrent virtual threads)
    ├─→ ConsentAdminProjectionHandler → SQL tables
    ├─→ ConsentDecisionProjection → Redis graph
    ├─→ BillingProjectionHandler → Subscription/Invoice tables
    └─→ Metrics aggregation
```

### Multi-Tenancy

- **Tenant Isolation**: 
  - Row-level security (RLS) via `tenant_id` column
  - `TenantContext.require()` enforces tenant in every operation
  - Virtual thread-local ScopedValue prevents concurrent tenant cross-contamination
  
- **Schema Isolation** (Consent):
  - Runtime: `cveil_consent` schema (multi-tenant)
  - Codegen: H2 PUBLIC schema (single-tenant for SQL generation)

### Plugin Architecture

- **ConsentDecisionService**: Redis-based (ConsentDecisionQueryServiceRedis)
- **SubjectDsarService**: jOOQ-backed (JooqSubjectDsarService)
- **PaymentProcessorService**: Stripe-backed (StripePaymentProcessorService)
- **PlanQueryService**: SQL-backed (not shown, but in platform-core-command)

---

## 8. KEY FILES SUMMARY

| Component | File Path |
|-----------|-----------|
| **Consent Grant/Revoke** | `/tenant/cveil-consent-admin-command/.../GrantConsentCommandActuator.java` |
| **Consent Ledger** | `/tenant/cveil-consent-admin-jooq-common/.../ConsentLedgerRepository.java` |
| **Event Types** | `/cveil-core/src/main/java/.../ConsentEventTypes.java` |
| **Consent Evaluation** | `/tenant/cveil-consent-admin-query-jooq/.../EvaluateConsentQueryActuator.java` |
| **Decision Cache (Redis)** | `/tenant/cveil-consent-decision-projection-redis/.../ConsentDecisionQueryServiceRedis.java` |
| **Subject Registration** | `/tenant/cveil-consent-admin-command/.../RegisterSubjectCommandActuator.java` |
| **DSAR Service** | `/tenant/cveil-subject-dsar-jooq/.../JooqSubjectDsarService.java` |
| **Consent Projection** | `/tenant/cveil-consent-admin-projection-jooq/.../ConsentAdminProjectionHandler.java` |
| **Billing Command** | `/platform/cveil-billing-command/src/main/java/.../CreateSubscriptionCommandActuator.java` |
| **Stripe Service** | `/platform/cveil-payment-proc-stripe/.../StripePaymentProcessorService.java` |
| **Webhook Handler** | `/cveil-rest-api/src/main/java/...resource/WebhookResource.java` |
| **Billable Metric Filter** | `/cveil-rest-api/src/main/java/.../filter/BillableMetricFilter.java` |
| **Governance Seed** | `/tenant/cveil-consent-admin-command/.../SeedConsentGovernanceCommandActuator.java` |
| **Consent Schema** | `/tenant/cveil-consent-admin-jooq-common/.../migration/consent/V1__create_unified_consent_schema.sql` |
| **Billing Schema** | `/platform/cveil-billing-schema/.../migration/platform-billing/V1__platform_billing_create_schema.sql` |
| **Plans Table** | `/platform/cveil-core-schema/.../migration/platform-core/V3__create_plan_table.sql` |
| **REST Resources** | `/cveil-rest-api/src/main/java/.../resource/ConsentResource.java`, `BillingResource.java` |

