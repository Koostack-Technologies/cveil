/**
 * Java 21 SDK for the cveil consent masking engine.
 *
 * <p>Entry point: {@link com.koostack.cveil.sdk.CveilClient}
 *
 * <p>Architecture mirrors the JavaScript {@code @cveil/sdk-js}:
 *
 * <pre>
 * Java (CveilClient)
 *   │ JSON strings over WASM FFI (Chicory)
 *   ▼
 * cveil-sdk-core.wasm (Rust → WASI)
 *   ├── Graph Cache (consent topology)
 *   ├── Decision Cache (LRU + TTL)
 *   ├── Consent Evaluator (graph traversal)
 *   ├── Masking Engine (5 strategies)
 *   └── Invalidation Handler (SSE dedup)
 * </pre>
 */
package com.koostack.cveil.sdk;
