use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::models::MaskingType;

/// Apply a MaskingType to a JSON value, returning the masked value.
pub fn apply_mask(value: &Value, masking_type: MaskingType) -> Value {
    match masking_type {
        MaskingType::Plaintext => value.clone(),
        MaskingType::RedactAll => redact_all(value),
        MaskingType::RedactUpToLast4 => redact_up_to_last_4(value),
        MaskingType::Hash256 => hash256(value),
        MaskingType::RedactLocal => redact_local(value),
    }
}

fn redact_all(value: &Value) -> Value {
    match value {
        Value::String(_) => Value::String("***".to_string()),
        Value::Number(n) => {
            if n.is_f64() {
                Value::from(0.0_f64)
            } else {
                Value::from(0)
            }
        }
        Value::Bool(_) => Value::Bool(false),
        Value::Null | Value::Object(_) | Value::Array(_) => Value::Null,
    }
}

fn redact_up_to_last_4(value: &Value) -> Value {
    match value {
        Value::String(s) => {
            let char_count = s.chars().count();
            if char_count <= 4 {
                Value::String("****".to_string())
            } else {
                let visible_start = char_count - 4;
                let last4: String = s.chars().skip(visible_start).collect();
                let masked = format!("{}{}", "*".repeat(visible_start), last4);
                Value::String(masked)
            }
        }
        // Non-string types fall back to REDACT_ALL behavior
        _ => redact_all(value),
    }
}

fn hash256(value: &Value) -> Value {
    match value {
        Value::String(s) => {
            let mut hasher = Sha256::new();
            hasher.update(s.as_bytes());
            let hash = hasher.finalize();
            Value::String(hex::encode(hash))
        }
        Value::Number(n) => {
            let mut hasher = Sha256::new();
            hasher.update(n.to_string().as_bytes());
            let hash = hasher.finalize();
            Value::String(hex::encode(hash))
        }
        // Bool/null/complex types fall back to REDACT_ALL
        _ => redact_all(value),
    }
}

fn redact_local(value: &Value) -> Value {
    match value {
        Value::String(s) => {
            if let Some(at_pos) = s.find('@') {
                let domain = &s[at_pos..];
                let masked_len = at_pos.max(4);
                Value::String(format!("{}{}", "*".repeat(masked_len), domain))
            } else {
                redact_all(value)
            }
        }
        _ => redact_all(value),
    }
}

/// Hex encoding using a lookup table (avoids format! per byte).
mod hex {
    const HEX_CHARS: &[u8; 16] = b"0123456789abcdef";

    pub fn encode(bytes: impl AsRef<[u8]>) -> String {
        let bytes = bytes.as_ref();
        let mut out = String::with_capacity(bytes.len() * 2);
        for &b in bytes {
            out.push(HEX_CHARS[(b >> 4) as usize] as char);
            out.push(HEX_CHARS[(b & 0x0f) as usize] as char);
        }
        out
    }
}

/// Walk a JSON object tree and mask leaf fields that have resolutions.
///
/// `resolutions` maps field paths (e.g., "email", "home.street") to their MaskingType.
/// Unmatched fields are passed through unchanged.
pub fn mask_json_tree(value: &mut Value, resolutions: &std::collections::HashMap<String, MaskingType>) {
    walk_and_mask(value, "", resolutions);
}

fn walk_and_mask(
    value: &mut Value,
    current_path: &str,
    resolutions: &std::collections::HashMap<String, MaskingType>,
) {
    match value {
        Value::Object(map) => {
            for (key, child) in map.iter_mut() {
                let child_path = if current_path.is_empty() {
                    key.clone()
                } else {
                    format!("{current_path}.{key}")
                };

                // Check if this field itself has a resolution (leaf or not)
                if let Some(&mt) = resolutions.get(&child_path) {
                    if mt != MaskingType::Plaintext {
                        *child = apply_mask(child, mt);
                        continue; // Don't recurse into masked subtrees
                    }
                }

                // Recurse into objects and arrays
                walk_and_mask(child, &child_path, resolutions);
            }
        }
        Value::Array(arr) => {
            for item in arr.iter_mut() {
                walk_and_mask(item, current_path, resolutions);
            }
        }
        _ => {
            // Leaf value at a path — check if it should be masked
            if !current_path.is_empty() {
                if let Some(&mt) = resolutions.get(current_path) {
                    if mt != MaskingType::Plaintext {
                        *value = apply_mask(value, mt);
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    // --- apply_mask unit tests ---

    #[test]
    fn plaintext_passthrough() {
        let v = Value::String("hello".into());
        assert_eq!(apply_mask(&v, MaskingType::Plaintext), v);
    }

    #[test]
    fn redact_all_string() {
        let v = Value::String("secret".into());
        assert_eq!(apply_mask(&v, MaskingType::RedactAll), Value::String("***".into()));
    }

    #[test]
    fn redact_all_number() {
        let v = Value::from(42);
        assert_eq!(apply_mask(&v, MaskingType::RedactAll), Value::from(0));
    }

    #[test]
    fn redact_all_float() {
        let v = Value::from(3.14);
        assert_eq!(apply_mask(&v, MaskingType::RedactAll), Value::from(0.0));
    }

    #[test]
    fn redact_all_bool() {
        assert_eq!(apply_mask(&Value::Bool(true), MaskingType::RedactAll), Value::Bool(false));
    }

    #[test]
    fn redact_all_null() {
        assert_eq!(apply_mask(&Value::Null, MaskingType::RedactAll), Value::Null);
    }

    #[test]
    fn redact_up_to_last_4_long_string() {
        let v = Value::String("1234567890".into());
        assert_eq!(
            apply_mask(&v, MaskingType::RedactUpToLast4),
            Value::String("******7890".into())
        );
    }

    #[test]
    fn redact_up_to_last_4_short_string() {
        let v = Value::String("abc".into());
        assert_eq!(
            apply_mask(&v, MaskingType::RedactUpToLast4),
            Value::String("****".into())
        );
    }

    #[test]
    fn redact_up_to_last_4_number_falls_back() {
        let v = Value::from(42);
        assert_eq!(apply_mask(&v, MaskingType::RedactUpToLast4), Value::from(0));
    }

    #[test]
    fn redact_up_to_last_4_multibyte_utf8() {
        // "a🎉bcde" = 1 ASCII + 1 emoji (4 bytes) + 4 ASCII = 6 chars
        let v = Value::String("a🎉bcde".into());
        let result = apply_mask(&v, MaskingType::RedactUpToLast4);
        // Last 4 chars are "bcde", first 2 chars masked
        assert_eq!(result, Value::String("**bcde".into()));
    }

    #[test]
    fn redact_up_to_last_4_all_emoji() {
        // 5 emoji chars, each 4 bytes = 20 bytes
        let v = Value::String("🎉🎊🎋🎍🎎".into());
        let result = apply_mask(&v, MaskingType::RedactUpToLast4);
        // Last 4 chars visible, first 1 masked
        assert_eq!(result, Value::String("*🎊🎋🎍🎎".into()));
    }

    #[test]
    fn hash256_string() {
        let v = Value::String("hello".into());
        let result = apply_mask(&v, MaskingType::Hash256);
        // SHA-256 of "hello"
        assert_eq!(
            result,
            Value::String("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into())
        );
    }

    #[test]
    fn hash256_number() {
        let v = Value::from(42);
        let result = apply_mask(&v, MaskingType::Hash256);
        if let Value::String(s) = result {
            assert_eq!(s.len(), 64); // SHA-256 hex = 64 chars
        } else {
            panic!("Expected String");
        }
    }

    #[test]
    fn hash256_bool_falls_back() {
        assert_eq!(apply_mask(&Value::Bool(true), MaskingType::Hash256), Value::Bool(false));
    }

    #[test]
    fn redact_local_email() {
        let v = Value::String("john.doe@example.com".into());
        assert_eq!(
            apply_mask(&v, MaskingType::RedactLocal),
            Value::String("********@example.com".into())
        );
    }

    #[test]
    fn redact_local_short_email() {
        let v = Value::String("a@b.com".into());
        assert_eq!(
            apply_mask(&v, MaskingType::RedactLocal),
            Value::String("****@b.com".into())
        );
    }

    #[test]
    fn redact_local_no_at_falls_back() {
        let v = Value::String("not_an_email".into());
        assert_eq!(
            apply_mask(&v, MaskingType::RedactLocal),
            Value::String("***".into())
        );
    }

    // --- mask_json_tree tests ---

    #[test]
    fn mask_flat_object() {
        let mut obj: Value = serde_json::json!({
            "name": "Alice",
            "email": "alice@example.com",
            "age": 30
        });

        let mut resolutions = HashMap::new();
        resolutions.insert("email".to_string(), MaskingType::RedactLocal);
        resolutions.insert("age".to_string(), MaskingType::RedactAll);

        mask_json_tree(&mut obj, &resolutions);

        assert_eq!(obj["name"], Value::String("Alice".into())); // unmasked
        assert_eq!(obj["email"], Value::String("*****@example.com".into()));
        assert_eq!(obj["age"], Value::from(0));
    }

    #[test]
    fn mask_nested_object() {
        let mut obj: Value = serde_json::json!({
            "name": "Bob",
            "address": {
                "street": "123 Main St",
                "city": "Springfield"
            }
        });

        let mut resolutions = HashMap::new();
        resolutions.insert("address.street".to_string(), MaskingType::RedactAll);

        mask_json_tree(&mut obj, &resolutions);

        assert_eq!(obj["address"]["street"], Value::String("***".into()));
        assert_eq!(obj["address"]["city"], Value::String("Springfield".into()));
    }

    #[test]
    fn mask_array_items() {
        let mut obj: Value = serde_json::json!({
            "users": [
                {"email": "a@b.com", "name": "A"},
                {"email": "c@d.com", "name": "C"}
            ]
        });

        let mut resolutions = HashMap::new();
        resolutions.insert("users.email".to_string(), MaskingType::Hash256);

        mask_json_tree(&mut obj, &resolutions);

        // Names should be untouched
        assert_eq!(obj["users"][0]["name"], Value::String("A".into()));
        assert_eq!(obj["users"][1]["name"], Value::String("C".into()));
        // Emails should be hashed (64-char hex strings)
        if let Value::String(ref s) = obj["users"][0]["email"] {
            assert_eq!(s.len(), 64);
        } else {
            panic!("Expected hashed email");
        }
    }

    #[test]
    fn mask_subtree_when_parent_redacted() {
        let mut obj: Value = serde_json::json!({
            "profile": {
                "bio": "I love coding",
                "social": {
                    "twitter": "@alice"
                }
            }
        });

        let mut resolutions = HashMap::new();
        // Masking the entire "profile" subtree
        resolutions.insert("profile".to_string(), MaskingType::RedactAll);

        mask_json_tree(&mut obj, &resolutions);

        // The whole profile object should become null (REDACT_ALL on object)
        assert_eq!(obj["profile"], Value::Null);
    }

    #[test]
    fn empty_resolutions_passthrough() {
        let original = serde_json::json!({"a": 1, "b": "c"});
        let mut obj = original.clone();
        mask_json_tree(&mut obj, &HashMap::new());
        assert_eq!(obj, original);
    }

    #[test]
    fn plaintext_resolution_passthrough() {
        let original = serde_json::json!({"email": "a@b.com"});
        let mut obj = original.clone();
        let mut res = HashMap::new();
        res.insert("email".to_string(), MaskingType::Plaintext);
        mask_json_tree(&mut obj, &res);
        assert_eq!(obj, original);
    }
}
