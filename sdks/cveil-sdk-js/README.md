# @cveil/sdk-js

JavaScript/Node.js facade for the **cveil-sdk-core** WASM consent masking engine.

## Quick Start

```typescript
import { CveilClient } from "@cveil/sdk-js";

const client = await CveilClient.create({
  config: {
    apiBaseUrl: "https://api.operio.example.com",
    apiToken: "cveil_your_token_here",
    defaultJurisdiction: "EU",
  },
});

// Mask a user record based on consent graph
const result = await client.mask("user-123", "User", {
  email: "alice@example.com",
  name: "Alice",
  phone: "555-1234",
}, "Marketing");

console.log(result.data);
// { email: "***", name: "Alice", phone: "***" }
// (fields masked per consent — email/phone denied, name not registered)
```

## Requirements

- Node.js >= 20.0.0 (for WASI support)
- The `cveil_sdk_core.wasm` binary (built from `crates/cveil-sdk-core`)

## Building

```bash
# Build the WASM binary + compile TypeScript
npm run build

# Or step by step:
npm run build:wasm   # Builds Rust → copies .wasm to wasm/
npm run build:ts     # Compiles TypeScript → dist/
```

## Testing

```bash
npm test
```

## API

### `CveilClient.create(options)`

Creates and initializes a client. Loads the WASM module, fetches the consent graph, and connects SSE.

### `client.mask(subject, entity, data, purpose)`

Masks a JSON object based on consent evaluation. Auto-fetches missing decisions.

### `client.maskWithJurisdiction(subject, entity, data, purpose, jurisdiction)`

Same as `mask()` but with explicit jurisdiction override.

### `client.maskBatch(subject, entity, data[], purpose)`

Masks an array of objects (same subject/entity/purpose).

### `client.evaluate(subject, entity, fields, purpose)`

Returns per-field masking resolution without applying masks.

### `client.status()`

Returns SDK health: connection state, cache sizes, etc.

### `client.shutdown()`

Graceful shutdown — flushes caches and disconnects SSE.

## Architecture

```
Node.js (CveilClient)
  │ JSON strings over WASM FFI
  ▼
cveil-sdk-core.wasm (Rust → WASI)
  ├── Graph Cache (consent topology)
  ├── Decision Cache (LRU + TTL)
  ├── Consent Evaluator (graph traversal)
  ├── Masking Engine (5 strategies)
  └── Invalidation Handler (SSE dedup)
```

The JS facade drives all I/O (HTTP, SSE). The WASM core is a pure computation engine.
