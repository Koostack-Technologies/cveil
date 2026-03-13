//! SSE invalidation processing and connection state management.

pub mod handler;
pub mod state;

pub use handler::{InvalidationEffect, InvalidationHandler};
pub use state::{ConnectionState, ConnectionTracker};
