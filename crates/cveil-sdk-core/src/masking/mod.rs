//! Masking engine: JSON tree walker + MaskingType application.

pub mod engine;

pub use engine::{apply_mask, mask_json_tree};
