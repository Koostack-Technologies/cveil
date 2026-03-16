# @cveil/sdk-java

Java 21 facade for the **cveil-sdk-core** WASM consent masking engine.

## Quick Start

```java
import com.koostack.cveil.sdk.*;
import java.nio.file.Path;
import java.util.Map;

var client = CveilClient.create(new CveilClientOptions(
    new CveilConfig(
        "https://api.cveil.example.com",
        "cveil_your_token_here",
        "EU"
    ),
    Path.of("wasm/cveil_sdk_core.wasm")
));

// Mask a user record based on consent graph
var result = client.mask("user-123", "User", Map.of(
    "email", "alice@example.com",
    "name", "Alice",
    "phone", "555-1234"
), "Marketing");

System.out.println(result.data());
// {email=***, name=Alice, phone=***}
// (fields masked per consent — email/phone denied, name not registered)
```

## Requirements

- Java 21+
- The `cveil_sdk_core.wasm` binary (built from `crates/cveil-sdk-core`)

## Building

```bash
# From the repository root
./gradlew :sdks:cveil-sdk-java:build
```

## API

### `CveilClient.create(options)`

Creates and initializes a client. Loads the WASM module, fetches the consent graph, and connects SSE.

### `client.mask(subject, entity, data, purpose)`

Masks a JSON object based on consent evaluation. Auto-fetches missing decisions.

### `client.maskWithJurisdiction(subject, entity, data, purpose, jurisdiction)`

Same as `mask()` but with explicit jurisdiction override.

### `client.maskBatch(subject, entity, data[], purpose)`

Masks a list of objects (same subject/entity/purpose).

### `client.evaluate(subject, entity, fields, purpose)`

Returns per-field masking resolution without applying masks.

### `client.status()`

Returns SDK health: connection state, cache sizes, etc.

### `client.shutdown()` / `client.close()`

Graceful shutdown — flushes caches and disconnects SSE. Implements `AutoCloseable`.

### Consent Operations

- `grant(request)` / `revoke(request)` — Single consent grant/revoke
- `grantAll(request)` / `revokeAll(request)` — Batch operations (up to 1000)
- `evaluateConsent(subject, request)` — Evaluate consent scopes
- `getSubjectSummary(subject)` / `getSubjectHistory(subject)` — Consent views

### Subject Operations

- `listSubjects()` / `getSubject(name)` / `registerSubject(request)`
- `updateSubject(name, request)` / `removeSubject(name)`
- `exportSubjectData(subject)` — GDPR Article 20
- `deleteSubjectData(subject)` — GDPR Article 17
- `shredDsarKey(name)` — Irreversible key destruction

### Governance Operations

- Purposes: `listPurposes()`, `getPurpose()`, `registerPurpose()`, `removePurpose()`
- Policies: `addPurposePolicy()`, `removePurposePolicy()`
- Regulation links: `linkPurposeToRegulation()`, `unlinkPurposeFromRegulation()`
- Regulations: `listRegulations()`, `getRegulation()`, `registerRegulation()`, `removeRegulation()`
- Jurisdictions: `listJurisdictions()`, `getJurisdiction()`, `registerJurisdiction()`, `updateJurisdiction()`, `removeJurisdiction()`
- Jurisdiction links: `addJurisdictionToRegulation()`, `removeJurisdictionFromRegulation()`

### Catalogue Operations

- Fields: `listFields()`, `registerField()`, `removeField()`
- Entities: `listEntities()`, `getEntity()`, `registerEntity()`, `updateEntity()`, `removeEntity()`
- Categories: `listCategories()`, `getCategory()`, `registerCategory()`, `updateCategory()`, `removeCategory()`
- Field-Category links: `assignFieldToCategory()`, `unassignFieldFromCategory()`

## Architecture

```
Java (CveilClient)
  │ JSON strings over WASM FFI (Chicory)
  ▼
cveil-sdk-core.wasm (Rust → WASI)
  ├── Graph Cache (consent topology)
  ├── Decision Cache (LRU + TTL)
  ├── Consent Evaluator (graph traversal)
  ├── Masking Engine (5 strategies)
  └── Invalidation Handler (SSE dedup)
```

The Java facade drives all I/O (HTTP, SSE). The WASM core is a pure computation engine.

## Dependencies

- **Jackson** — JSON serialization
- **Chicory** — Pure-Java WASM runtime with WASI support
- **java.net.http.HttpClient** — Built-in HTTP (no framework dependency)
- **Virtual Threads** — Java 21 lightweight concurrency for SSE streaming
