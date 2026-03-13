//! cveil-sdk-core: WASM consent masking SDK core.
//!
//! Provides local consent graph evaluation and field masking
//! for client applications. The WASM core is a pure computation engine —
//! all I/O (HTTP, SSE) is driven by the host language facade.

pub mod models;
pub mod graph;
pub mod cache;
pub mod evaluator;
pub mod masking;
pub mod invalidation;
pub mod ffi;
pub mod error;
pub mod config;

