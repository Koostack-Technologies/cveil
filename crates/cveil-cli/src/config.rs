use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::cli::{ConfigArgs, StartArgs};

/// CLI-extended config that wraps the SDK config with daemon-specific fields.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonConfig {
    // ── SDK fields ──────────────────────────────────────────────
    pub api_base_url: String,
    pub api_token: String,
    pub default_jurisdiction: String,
    #[serde(default = "default_stale_mode")]
    pub stale_mode: String,
    #[serde(default = "default_disconnect_threshold_ms")]
    pub disconnect_threshold_ms: u64,
    #[serde(default = "default_decision_cache_max_size")]
    pub decision_cache_max_size: usize,
    #[serde(default = "default_decision_cache_ttl_ms")]
    pub decision_cache_ttl_ms: u64,
    #[serde(default = "default_dedup_window_size")]
    pub dedup_window_size: usize,

    // ── Daemon-specific fields ──────────────────────────────────
    #[serde(default = "default_socket_path")]
    pub socket_path: String,
    #[serde(default = "default_log_level")]
    pub log_level: String,
}

fn default_stale_mode() -> String {
    "conservativeMask".into()
}
fn default_disconnect_threshold_ms() -> u64 {
    60_000
}
fn default_decision_cache_max_size() -> usize {
    10_000
}
fn default_decision_cache_ttl_ms() -> u64 {
    300_000
}
fn default_dedup_window_size() -> usize {
    500
}
fn default_socket_path() -> String {
    "/tmp/cveil.sock".into()
}
fn default_log_level() -> String {
    "info".into()
}

/// Expand `~` to the user's home directory.
fn expand_tilde(path: &str) -> PathBuf {
    if let Some(rest) = path.strip_prefix("~/") {
        if let Some(home) = dirs::home_dir() {
            return home.join(rest);
        }
    }
    PathBuf::from(path)
}

impl DaemonConfig {
    /// Load config with precedence: CLI flags > env vars > config file > defaults.
    pub fn load(start: &StartArgs) -> Result<Self, String> {
        let config_path = expand_tilde(&start.config);

        // 1. Load from file (or use empty defaults)
        let mut config = if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)
                .map_err(|e| format!("Failed to read config file {}: {e}", config_path.display()))?;
            serde_json::from_str::<DaemonConfig>(&content)
                .map_err(|e| format!("Invalid config JSON in {}: {e}", config_path.display()))?
        } else {
            return Err(format!(
                "Config file not found: {}. Create it or use --config <path>.",
                config_path.display()
            ));
        };

        // 2. Overlay env vars (CVEIL_*)
        if let Ok(v) = std::env::var("CVEIL_API_BASE_URL") {
            config.api_base_url = v;
        }
        if let Ok(v) = std::env::var("CVEIL_API_TOKEN") {
            config.api_token = v;
        }
        if let Ok(v) = std::env::var("CVEIL_DEFAULT_JURISDICTION") {
            config.default_jurisdiction = v;
        }
        if let Ok(v) = std::env::var("CVEIL_SOCKET_PATH") {
            config.socket_path = v;
        }
        if let Ok(v) = std::env::var("CVEIL_LOG_LEVEL") {
            config.log_level = v;
        }
        if let Ok(v) = std::env::var("CVEIL_STALE_MODE") {
            config.stale_mode = v;
        }
        if let Ok(v) = std::env::var("CVEIL_DISCONNECT_THRESHOLD_MS") {
            config.disconnect_threshold_ms = v
                .parse()
                .map_err(|_| "CVEIL_DISCONNECT_THRESHOLD_MS must be a number".to_string())?;
        }

        // 3. Overlay CLI flags (highest precedence)
        if let Some(ref socket) = start.socket {
            config.socket_path = socket.clone();
        }
        if let Some(ref level) = start.log_level {
            config.log_level = level.clone();
        }

        // 4. Validate
        config.validate()?;

        Ok(config)
    }

    fn validate(&self) -> Result<(), String> {
        if self.api_base_url.is_empty() {
            return Err("apiBaseUrl must not be empty".into());
        }
        if self.api_token.is_empty() {
            return Err("apiToken must not be empty".into());
        }
        if !is_valid_token_format(&self.api_token) {
            return Err(
                "apiToken must be a cveil personal access token (cveil_...) or a JWT".into(),
            );
        }
        if self.default_jurisdiction.is_empty() {
            return Err("defaultJurisdiction must not be empty".into());
        }
        if self.decision_cache_max_size == 0 {
            return Err("decisionCacheMaxSize must be positive".into());
        }
        if !["conservativeMask", "apiFallback"].contains(&self.stale_mode.as_str()) {
            return Err(format!(
                "staleMode must be 'conservativeMask' or 'apiFallback', got '{}'",
                self.stale_mode
            ));
        }
        Ok(())
    }

    /// Returns the detected token type for logging purposes.
    pub fn token_type(&self) -> &'static str {
        if self.api_token.starts_with("cveil_") {
            "personal-access-token"
        } else if is_jwt(&self.api_token) {
            "jwt"
        } else {
            "unknown"
        }
    }

    /// Produce the JSON config string that cveil_init() expects.
    pub fn to_sdk_config_json(&self) -> Result<String, String> {
        let sdk = serde_json::json!({
            "apiBaseUrl": self.api_base_url,
            "apiToken": self.api_token,
            "defaultJurisdiction": self.default_jurisdiction,
            "staleMode": self.stale_mode,
            "disconnectThresholdMs": self.disconnect_threshold_ms,
            "decisionCacheMaxSize": self.decision_cache_max_size,
            "decisionCacheTtlMs": self.decision_cache_ttl_ms,
            "dedupWindowSize": self.dedup_window_size,
        });
        serde_json::to_string(&sdk).map_err(|e| e.to_string())
    }
}

/// `cveil config` — print resolved config (no daemon required).
pub fn run_config_show(args: ConfigArgs) -> i32 {
    // Build a minimal StartArgs to reuse the load logic
    let start = StartArgs {
        config: args.config,
        socket: None,
        daemonize: false,
        pid_file: String::new(),
        log_level: None,
    };

    match DaemonConfig::load(&start) {
        Ok(config) => {
            let json = serde_json::to_string_pretty(&config).unwrap();
            println!("{json}");
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

/// Check if the token has a valid format — either a `cveil_` PAT or a JWT.
fn is_valid_token_format(token: &str) -> bool {
    token.starts_with("cveil_") || is_jwt(token)
}

/// Check if the token looks like a JWT (three dot-separated base64url segments).
fn is_jwt(token: &str) -> bool {
    let parts: Vec<&str> = token.splitn(4, '.').collect();
    parts.len() == 3 && parts.iter().all(|p| !p.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_config(dir: &std::path::Path, content: &str) -> PathBuf {
        let path = dir.join("config.json");
        let mut f = std::fs::File::create(&path).unwrap();
        f.write_all(content.as_bytes()).unwrap();
        path
    }

    #[test]
    fn loads_valid_config_file() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "cveil_test123",
                "defaultJurisdiction": "EU"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let config = DaemonConfig::load(&start).unwrap();
        assert_eq!(config.api_base_url, "https://api.example.com");
        assert_eq!(config.default_jurisdiction, "EU");
        assert_eq!(config.socket_path, "/tmp/cveil.sock");
        assert_eq!(config.decision_cache_max_size, 10_000);
    }

    #[test]
    fn cli_flags_override_file() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "cveil_test123",
                "defaultJurisdiction": "EU",
                "socketPath": "/tmp/original.sock"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: Some("/tmp/custom.sock".into()),
            daemonize: false,
            pid_file: String::new(),
            log_level: Some("debug".into()),
        };
        let config = DaemonConfig::load(&start).unwrap();
        assert_eq!(config.socket_path, "/tmp/custom.sock");
        assert_eq!(config.log_level, "debug");
    }

    #[test]
    fn rejects_invalid_stale_mode() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "cveil_test123",
                "defaultJurisdiction": "EU",
                "staleMode": "invalid"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let err = DaemonConfig::load(&start).unwrap_err();
        assert!(err.contains("staleMode"));
    }

    #[test]
    fn accepts_jwt_token() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSIsInRlbmFudF9pZCI6InQxIn0.signature",
                "defaultJurisdiction": "EU"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let config = DaemonConfig::load(&start).unwrap();
        assert!(config.api_token.contains('.'));
        assert_eq!(config.token_type(), "jwt");
    }

    #[test]
    fn accepts_pat_token() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "cveil_test123",
                "defaultJurisdiction": "EU"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let config = DaemonConfig::load(&start).unwrap();
        assert_eq!(config.token_type(), "personal-access-token");
    }

    #[test]
    fn rejects_invalid_token_format() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "some-random-string",
                "defaultJurisdiction": "EU"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let err = DaemonConfig::load(&start).unwrap_err();
        assert!(err.contains("apiToken"));
    }

    #[test]
    fn missing_config_errors() {
        let start = StartArgs {
            config: "/nonexistent/path.json".into(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let err = DaemonConfig::load(&start).unwrap_err();
        assert!(err.contains("not found"));
    }

    #[test]
    fn is_jwt_detects_jwt_format() {
        assert!(super::is_jwt("a.b.c"));
        assert!(super::is_jwt("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.sig"));
        assert!(!super::is_jwt("cveil_test123"));
        assert!(!super::is_jwt("no-dots-here"));
        assert!(!super::is_jwt("one.dot"));
        assert!(!super::is_jwt("too.many.dots.here"));
        assert!(!super::is_jwt("..empty"));
    }

    #[test]
    fn to_sdk_config_json_roundtrips() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_config(
            tmp.path(),
            r#"{
                "apiBaseUrl": "https://api.example.com",
                "apiToken": "cveil_test123",
                "defaultJurisdiction": "EU"
            }"#,
        );
        let start = StartArgs {
            config: path.to_str().unwrap().to_string(),
            socket: None,
            daemonize: false,
            pid_file: String::new(),
            log_level: None,
        };
        let config = DaemonConfig::load(&start).unwrap();
        let sdk_json = config.to_sdk_config_json().unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&sdk_json).unwrap();
        assert_eq!(parsed["apiBaseUrl"], "https://api.example.com");
        assert_eq!(parsed["staleMode"], "conservativeMask");
    }
}
