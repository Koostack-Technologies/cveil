use serde::{Deserialize, Serialize};

/// Stale mode fallback behavior when SSE connection is lost.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum StaleMode {
    /// Call /evaluate API for each mask() request (accurate but adds latency).
    ApiFallback,
    /// Mask all sensitive fields with their `onDenied` MaskingType (fast, over-masks).
    ConservativeMask,
}

/// SDK configuration provided at initialization.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SdkConfig {
    /// Base URL of the cveil REST API (e.g., "https://api.cveil.example.com").
    pub api_base_url: String,

    /// API token (`cveil_...`) for authentication.
    pub api_token: String,

    /// Default jurisdiction for masking resolution (e.g., "EU", "California").
    pub default_jurisdiction: String,

    /// Fallback behavior when SSE is disconnected beyond the threshold.
    #[serde(default = "default_stale_mode")]
    pub stale_mode: StaleMode,

    /// Milliseconds of SSE disconnection before entering stale mode.
    #[serde(default = "default_disconnect_threshold_ms")]
    pub disconnect_threshold_ms: u64,

    /// Maximum number of subject consent decisions to cache.
    #[serde(default = "default_decision_cache_max_size")]
    pub decision_cache_max_size: usize,

    /// TTL in milliseconds for cached consent decisions.
    #[serde(default = "default_decision_cache_ttl_ms")]
    pub decision_cache_ttl_ms: u64,

    /// Number of recent SSE message IDs to track for deduplication.
    #[serde(default = "default_dedup_window_size")]
    pub dedup_window_size: usize,
}

fn default_stale_mode() -> StaleMode {
    StaleMode::ConservativeMask
}

fn default_disconnect_threshold_ms() -> u64 {
    60_000
}

fn default_decision_cache_max_size() -> usize {
    10_000
}

fn default_decision_cache_ttl_ms() -> u64 {
    300_000 // 5 minutes
}

fn default_dedup_window_size() -> usize {
    500
}

impl SdkConfig {
    /// Validate the configuration, returning an error message if invalid.
    pub fn validate(&self) -> Result<(), String> {
        if self.api_base_url.is_empty() {
            return Err("api_base_url must not be empty".into());
        }
        if self.api_token.is_empty() {
            return Err("api_token must not be empty".into());
        }
        if !self.api_token.starts_with("cveil_") {
            return Err("api_token must start with 'cveil_'".into());
        }
        if self.default_jurisdiction.is_empty() {
            return Err("default_jurisdiction must not be empty".into());
        }
        if self.decision_cache_max_size == 0 {
            return Err("decision_cache_max_size must be positive".into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_config() {
        let config = SdkConfig {
            api_base_url: "https://api.example.com".into(),
            api_token: "cveil_abc123".into(),
            default_jurisdiction: "EU".into(),
            stale_mode: StaleMode::ConservativeMask,
            disconnect_threshold_ms: 60_000,
            decision_cache_max_size: 10_000,
            decision_cache_ttl_ms: 300_000,
            dedup_window_size: 500,
        };
        assert!(config.validate().is_ok());
    }

    #[test]
    fn rejects_empty_base_url() {
        let config = SdkConfig {
            api_base_url: "".into(),
            api_token: "cveil_abc123".into(),
            default_jurisdiction: "EU".into(),
            stale_mode: StaleMode::ConservativeMask,
            disconnect_threshold_ms: 60_000,
            decision_cache_max_size: 10_000,
            decision_cache_ttl_ms: 300_000,
            dedup_window_size: 500,
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_non_cveil_token() {
        let config = SdkConfig {
            api_base_url: "https://api.example.com".into(),
            api_token: "bearer_xyz".into(),
            default_jurisdiction: "EU".into(),
            stale_mode: StaleMode::ConservativeMask,
            disconnect_threshold_ms: 60_000,
            decision_cache_max_size: 10_000,
            decision_cache_ttl_ms: 300_000,
            dedup_window_size: 500,
        };
        let err = config.validate().unwrap_err();
        assert!(err.contains("cveil_"));
    }

    #[test]
    fn deserializes_from_json() {
        let json = r#"{
            "apiBaseUrl": "https://api.example.com",
            "apiToken": "cveil_abc123",
            "defaultJurisdiction": "EU"
        }"#;
        let config: SdkConfig = serde_json::from_str(json).unwrap();
        assert_eq!(config.api_base_url, "https://api.example.com");
        assert_eq!(config.stale_mode, StaleMode::ConservativeMask);
        assert_eq!(config.decision_cache_max_size, 10_000);
    }
}
