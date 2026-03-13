use std::fmt;

/// SDK error types.
#[derive(Debug)]
pub enum SdkError {
    /// Configuration is invalid or missing required fields.
    InvalidConfig(String),
    /// JSON serialization or deserialization failed.
    JsonError(serde_json::Error),
    /// Graph data is missing or inconsistent.
    GraphError(String),
    /// Consent evaluation could not complete (cache miss, no fallback).
    EvaluationError(String),
    /// SDK is not initialized.
    NotInitialized,
    /// SDK is in stale mode and configured for API fallback.
    NeedsFetch {
        subject: String,
        policy_names: Vec<String>,
    },
}

impl fmt::Display for SdkError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SdkError::InvalidConfig(msg) => write!(f, "invalid config: {msg}"),
            SdkError::JsonError(e) => write!(f, "JSON error: {e}"),
            SdkError::GraphError(msg) => write!(f, "graph error: {msg}"),
            SdkError::EvaluationError(msg) => write!(f, "evaluation error: {msg}"),
            SdkError::NotInitialized => write!(f, "SDK not initialized"),
            SdkError::NeedsFetch { subject, policy_names } => {
                write!(f, "needs fetch for subject={subject}, policies={policy_names:?}")
            }
        }
    }
}

impl std::error::Error for SdkError {}

impl From<serde_json::Error> for SdkError {
    fn from(e: serde_json::Error) -> Self {
        SdkError::JsonError(e)
    }
}

pub type SdkResult<T> = Result<T, SdkError>;
