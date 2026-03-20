# Web Dashboard Architecture

## Overview

The `cveil-web` module is the administrative dashboard for Cveil. It is built as a **lightweight, vanilla JavaScript** application using Tailwind CSS 4 for styling, Vite for bundling, and D3.js for data visualization. The dashboard is compiled as an IIFE bundle (`cveil-web.iife.js`) and served as a static asset by the Quarkus REST API.

## Design Principles

The dashboard follows the **Sentinel Aesthetic** design system (documented in `design.md`):
- Deep midnight blue tonal palette (`#0b1326` base)
- No-line rule: structural boundaries via background color shifts, not borders
- Three-typeface system: Manrope (display), Inter (interface), Space Grotesk (technical)
- Glassmorphism for floating elements
- Teal primary accent (`#4fdbc8`) for CTAs and success states

## Build & Delivery

```
cveil-web/
├── src/index.js          → Entry point (imports all modules)
├── src/styles.css         → Tailwind CSS 4 base styles
├── vite.config.js         → IIFE library build configuration
└── dist/cveil-web.iife.js → Compiled bundle

Build: npm run build
  → Vite produces cveil-web.iife.js + cveil-web.css

Delivery:
  → Docker: Copied into Quarkus static resources at build time
  → Dev: npm run build:copy copies to META-INF/resources/
  → Served at: /js/cveil-web.iife.js and /css/cveil-web.css
```

The Vite configuration produces an IIFE (Immediately Invoked Function Expression) format so the bundle can be loaded with a single `<script>` tag without module loader setup. D3.js is externalized (loaded via CDN).

## Module Structure

```
cveil-web/src/
├── index.js              # Bootstrap: DPoP init, token restore, module exports
├── styles.css            # Tailwind CSS 4 base stylesheet
│
├── shared/               # Shared utilities
│   ├── api.js            #   HTTP client with DPoP and retry
│   ├── dpop.js           #   DPoP key generation and proof creation
│   ├── retry.js          #   Exponential backoff retry logic
│   ├── badge.js          #   Status badge components
│   ├── escape.js         #   HTML escaping utilities
│   ├── message.js        #   Toast notification system
│   ├── modal.js          #   Modal dialog framework
│   ├── panel.js          #   Sliding panel components
│   ├── tabs.js           #   Tab navigation components
│   ├── time.js           #   Date/time formatting
│   ├── ready.js          #   DOM ready utilities
│   └── index.js          #   Shared module barrel export
│
├── consent/              # Consent management UI
│   ├── index.js          #   Consent dashboard (grant/revoke/view)
│   ├── masking.js        #   Data masking playground
│   ├── playground.js     #   Interactive consent evaluation playground
│   └── wasm-bridge.js    #   WASM module loader for browser-side evaluation
│
├── catalogue/            # Data catalogue management
│   └── index.js          #   Entity, field, category CRUD UI
│
├── governance/           # Governance management
│   └── index.js          #   Purpose, regulation, jurisdiction CRUD UI
│
├── dsar/                 # DSAR management
│   └── index.js          #   DSAR request submission and tracking UI
│
├── billing/              # Billing management
│   ├── index.js          #   Subscription, invoice, payment method UI
│   └── usage-chart.js    #   D3.js usage chart visualization
│
├── analytics/            # Analytics dashboard
│   └── index.js          #   Consent and usage analytics with charts
│
├── reports/              # Reports
│   └── index.js          #   Audit log viewer and report generation
│
└── webhooks/             # Webhook management
    └── index.js          #   Webhook subscription CRUD and delivery status
```

## Authentication & Security

### DPoP (Demonstrating Proof-of-Possession)

The dashboard uses **DPoP tokens** (RFC 9449) for API authentication. This prevents token theft and replay attacks by binding each token to a cryptographic key pair held by the client.

**Flow:**
```
1. User logs in via Keycloak OIDC
   → Receives access_token + refresh_token
   → Stores in sessionStorage

2. initDpopKeys()
   → Generates ECDSA P-256 key pair
   → Stores JWK in sessionStorage

3. Every API request (via shared/api.js):
   → Creates DPoP proof JWT signed with private key
   → Includes: htm (HTTP method), htu (URL), iat, jti (nonce)
   → Attaches as DPoP header alongside Authorization: DPoP {token}

4. Server validates:
   → DPoP proof signature matches token's cnf.jkt thumbprint
   → Proof is fresh (iat within acceptable window)
   → jti is unique (replay prevention)
```

### Token Lifecycle

```
Bootstrap:
  index.js → sessionStorage.getItem('cveil_access_token')
  → Check expiration (JWT exp claim)
  → If valid → setAccessToken() + initDpopKeys()
  → If expired → Clear all tokens, redirect to login
```

## Real-Time Updates (SSE)

The dashboard uses **Server-Sent Events** for real-time consent ledger updates:

```
consent/index.js
    │
    └── @microsoft/fetch-event-source
        │
        └── GET /api/v1/consent/ledger/stream
            │
            └── Server pushes events:
                - consent.granted
                - consent.revoked
                - consent.expired
                → UI updates consent table in real-time
```

The `@microsoft/fetch-event-source` library provides better SSE support than the native `EventSource` API, including custom headers (for DPoP) and automatic reconnection.

## WASM Integration

The consent playground feature loads the Rust-compiled WASM module directly in the browser for local consent evaluation:

```
consent/wasm-bridge.js
    │
    ├── fetch('/wasm/cveil_sdk_core.wasm')
    ├── WebAssembly.instantiate(wasmBytes, imports)
    └── Exports:
        ├── evaluate_consent(scope) → ALLOW | DENY
        └── mask_field(value, strategy) → masked_value
```

This allows users to test consent evaluation logic in the browser without making API calls.

## E2E Testing

The `e2e/` directory contains Playwright tests that exercise the full dashboard UI:

```
e2e/tests/
├── auth/           # Login, signup, logout, onboarding flows
├── account/        # Profile management, access tokens
├── consent/        # Consent grant/revoke, graph, ledger SSE, subjects
├── catalogue/      # Catalogue CRUD operations
├── governance/     # Governance CRUD operations
├── dsar/           # DSAR workflow tests
├── billing/        # Billing and subscription management
├── analytics/      # Analytics dashboard
├── reports/        # Audit log and report generation
└── webhooks/       # Webhook management and sidebar
```

**Test Configuration:**
```bash
cd e2e
npm ci
npx playwright install
npx playwright test              # All tests
npx playwright test --grep @auth # Filter by tag
npx playwright test --ui         # Interactive UI mode
```
