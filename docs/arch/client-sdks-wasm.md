# Client SDKs & WASM Architecture

## Overview

Cveil provides client-side consent evaluation and data masking through a **Rust-based WASM engine** that runs identically in three environments: Java applications (via Chicory), Node.js applications (via WASI), and web browsers (via `wasm32-unknown-unknown`). This enables consent decisions to be evaluated locally without network round-trips to the Cveil API.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│              cveil-sdk-core (Rust)                        │
│                                                          │
│  Consent graph evaluator · Field masking engine           │
│  Cache management · Graph invalidation logic              │
│                                                          │
│  Compile targets:                                        │
│  ├── wasm32-wasip1          (Java SDK, JS SDK)           │
│  └── wasm32-unknown-unknown (Browser, consent banner)    │
└────────┬──────────────────────┬──────────────────────────┘
         │                      │
    ┌────▼──────────┐    ┌──────▼─────────────┐
    │ cveil-sdk-java│    │  cveil-sdk-js      │
    │ (Chicory WASM)│    │  (Node.js WASI)    │
    │               │    │                    │
    │ Java 21       │    │  Node.js 20+       │
    └───────────────┘    └──────┬─────────────┘
                                │
                         ┌──────▼─────────────┐
                         │ cveil-consent-banner│
                         │ (Browser WASM)      │
                         │                    │
                         │ <script> tag        │
                         └────────────────────┘
```

## Rust WASM Core (`crates/cveil-sdk-core`)

The core consent evaluation engine written in Rust.

**Capabilities:**
- Consent graph evaluation with field-level precedence
- Data field masking (redaction, hashing, tokenization)
- Local consent cache management
- Graph invalidation logic
- Configuration management

**Dependencies:**
- `serde` / `serde_json` — Serialization
- `sha2` — SHA-256 hashing for masking

**Compile Targets:**
| Target | Use Case | Feature Flag |
|--------|----------|-------------|
| `wasm32-wasip1` | Java SDK (Chicory), JS SDK (Node.js WASI) | default |
| `wasm32-unknown-unknown` | Browser embedding, consent banner | `browser` |

**Source Structure:**
```
cveil-sdk-core/src/
├── lib.rs           # Library entry point and WASM exports
├── config.rs        # Configuration types
├── error.rs         # Error types
├── models/          # Consent scope, decision, and field models
├── evaluator/       # Consent decision evaluation engine
├── graph/           # Consent graph data structure
├── cache/           # Local consent cache
├── invalidation/    # Cache invalidation logic
├── masking/         # Field masking strategies
└── ffi/             # Foreign function interface for WASM hosts
```

**Build Commands:**
```bash
# For Java/JS SDKs (WASI)
cargo build -p cveil-sdk-core --target wasm32-wasip1 --release

# For browser embedding
cargo build -p cveil-sdk-core --target wasm32-unknown-unknown --features browser --release
```

## Java SDK (`sdks/cveil-sdk-java`)

A Java 21 facade for the WASM consent engine, using the [Chicory](https://github.com/nicholasgasior/chicory) pure-Java WASM runtime (no native dependencies).

**Key Technologies:**
- Chicory WASM runtime (v1.1.0) — pure Java, no JNI
- Chicory WASI support — provides WASI system calls to the WASM module
- Jackson — JSON serialization between Java and WASM
- Java 21 — required for virtual thread compatibility

**Architecture:**
```
Java Application
    │
    ▼
CveilClient (facade)
    │
    ├── ConsentEvaluator  ──▶  WASM Module (cveil_sdk_core.wasm)
    │                           via Chicory Runtime
    ├── FieldMasker       ──▶  WASM Module
    │
    └── API Client        ──▶  HTTP calls to Cveil REST API
                                (consent graph sync, cache refresh)
```

**WASM Integration:**
The Gradle build compiles the Rust crate to `wasm32-wasip1` and copies the `.wasm` binary into `src/main/resources/wasm/`. At runtime, Chicory loads the WASM module from the classpath.

**Build:**
```bash
cd sdks/cveil-sdk-java
../gradlew build
# This automatically:
# 1. cargo build -p cveil-sdk-core --target wasm32-wasip1 --release
# 2. Copies .wasm to src/main/resources/wasm/
# 3. Compiles Java sources
# 4. Runs tests
```

## JavaScript SDK (`sdks/cveil-sdk-js`)

A TypeScript/JavaScript facade for the WASM consent engine.

**Runtime Support:**
- **Node.js 20+** — Uses WASI (`--experimental-wasi-unstable-preview1`)
- **Browser** — Uses `WebAssembly.instantiate` with the `browser` feature flag build

**Package Structure:**
```
@cveil/sdk-js
├── dist/
│   └── index.js          # Compiled TypeScript
├── wasm/
│   └── cveil_sdk_core.wasm  # WASI WASM binary
└── README.md
```

**Build:**
```bash
cd sdks/cveil-sdk-js
npm run build  # Compiles Rust to WASM + TypeScript to JS
```

## Consent Banner (`sdks/cveil-consent-banner`)

An embeddable consent collection widget that organizations can add to their websites with a single `<script>` tag.

**Features:**
- Lightweight, self-contained widget
- Fetches consent configuration from Cveil API (`/api/v1/banner-config`)
- Collects user consent choices
- Submits consent to public API (`/public/consent/`)
- WASM-powered local consent evaluation (optional)
- Customizable appearance (Vite build)

**Integration:**
```html
<!-- Drop-in consent banner -->
<script src="https://your-cveil.com/js/cveil-banner.min.js"></script>
```

**Build:**
```bash
cd sdks/cveil-consent-banner
npm run build  # Produces dist/cveil-banner.min.js
```

## CLI (`crates/cveil-cli`)

A Rust command-line tool and daemon for consent enforcement.

**Capabilities:**
- **Daemon mode** — Runs as a local proxy that intercepts and masks data based on consent decisions
- **Client mode** — CLI commands for consent evaluation and management
- **SSE streaming** — Subscribes to real-time consent change events
- **Graph visualization** — Terminal-based consent graph display

**Dependencies:**
- `cveil-sdk-core` — Core consent evaluation engine
- `clap` — CLI argument parsing
- `tokio` — Async runtime
- `axum` — HTTP server for daemon mode
- `reqwest` — HTTP client for API calls
- `tracing` — Structured logging

**Source Structure:**
```
cveil-cli/src/
├── main.rs       # Entry point and CLI argument parsing
├── cli.rs        # CLI command definitions (Clap)
├── config.rs     # Configuration file management
├── client.rs     # HTTP client for Cveil API
├── daemon.rs     # Masking proxy daemon
├── server.rs     # Axum HTTP server for daemon
├── graph.rs      # Consent graph visualization
├── sse.rs        # Server-Sent Events client
├── actor.rs      # Actor-based concurrency model
└── lib.rs        # Library exports
```

**Usage:**
```bash
# Start masking daemon
cveil daemon start --config cveil.toml

# Evaluate consent
cveil consent evaluate --subject user-123 --entity customers --field email --purpose marketing

# Stream consent changes
cveil stream --tenant acme-corp
```

## SDK Examples (`examples/cveil-java-sdk-examples`)

Demonstrates common SDK usage patterns:

| Example | Description |
|---------|-------------|
| `ExampleRunner.java` | Main runner that executes all examples |
| `ExampleConfig.java` | SDK configuration setup |
| `CatalogueSetup.java` | Setting up entities, fields, and categories |
| `GovernanceSetup.java` | Configuring purposes, regulations, and jurisdictions |
| `ConsentLifecycle.java` | Granting and revoking consent |
| `BatchOperations.java` | Batch consent operations |
| `SubjectManagement.java` | Subject registration and queries |
| `DataMasking.java` | Field masking with the WASM engine |
| `DsarCompliance.java` | DSAR export and erasure workflows |

## Web Dashboard WASM Bridge

The web dashboard (`cveil-web`) includes a WASM bridge for client-side consent evaluation:

```
cveil-web/src/consent/wasm-bridge.js
    │
    ├── Loads cveil_sdk_core.wasm (browser build)
    ├── Initializes WASM module with WebAssembly.instantiate
    └── Provides consent evaluation in the browser
        for the consent playground feature
```

The WASM binary is built by `npm run build:wasm` and copied to the REST API's static resources for CDN delivery.
