/// Connection state machine for SSE lifecycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// SSE connected and receiving events.
    Connected,
    /// SSE disconnected but within threshold — still trusting cache.
    Disconnected,
    /// Disconnected longer than threshold — cache is stale.
    Stale,
}

/// Tracks SSE connection state with timestamps for threshold evaluation.
pub struct ConnectionTracker {
    state: ConnectionState,
    /// When the connection was last lost (ms since epoch), or None if connected.
    disconnected_at: Option<u64>,
    /// Threshold in ms before transitioning from Disconnected to Stale.
    stale_threshold_ms: u64,
}

impl ConnectionTracker {
    pub fn new(stale_threshold_ms: u64) -> Self {
        Self {
            state: ConnectionState::Disconnected, // Start as disconnected until SSE connects
            disconnected_at: None,
            stale_threshold_ms,
        }
    }

    pub fn state(&self) -> ConnectionState {
        self.state
    }

    /// Call when SSE connection is established.
    pub fn on_connected(&mut self) {
        self.state = ConnectionState::Connected;
        self.disconnected_at = None;
    }

    /// Call when SSE connection is lost.
    pub fn on_disconnected(&mut self, now_ms: u64) {
        if self.state == ConnectionState::Connected {
            self.disconnected_at = Some(now_ms);
            self.state = ConnectionState::Disconnected;
        }
    }

    /// Check if we should transition to Stale based on elapsed time.
    /// Returns true if state changed.
    pub fn tick(&mut self, now_ms: u64) -> bool {
        if self.state == ConnectionState::Disconnected {
            if let Some(disc_at) = self.disconnected_at {
                if now_ms.saturating_sub(disc_at) >= self.stale_threshold_ms {
                    self.state = ConnectionState::Stale;
                    return true;
                }
            }
        }
        false
    }

    pub fn is_connected(&self) -> bool {
        self.state == ConnectionState::Connected
    }

    pub fn is_stale(&self) -> bool {
        self.state == ConnectionState::Stale
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_disconnected() {
        let tracker = ConnectionTracker::new(60_000);
        assert_eq!(tracker.state(), ConnectionState::Disconnected);
    }

    #[test]
    fn connect_then_disconnect() {
        let mut tracker = ConnectionTracker::new(60_000);
        tracker.on_connected();
        assert!(tracker.is_connected());

        tracker.on_disconnected(1000);
        assert_eq!(tracker.state(), ConnectionState::Disconnected);
        assert!(!tracker.is_stale());
    }

    #[test]
    fn transitions_to_stale_after_threshold() {
        let mut tracker = ConnectionTracker::new(60_000);
        tracker.on_connected();
        tracker.on_disconnected(1000);

        // Before threshold
        assert!(!tracker.tick(50_000));
        assert_eq!(tracker.state(), ConnectionState::Disconnected);

        // At threshold
        assert!(tracker.tick(61_000));
        assert!(tracker.is_stale());
    }

    #[test]
    fn reconnect_from_stale_resets() {
        let mut tracker = ConnectionTracker::new(60_000);
        tracker.on_connected();
        tracker.on_disconnected(1000);
        tracker.tick(70_000); // → Stale
        assert!(tracker.is_stale());

        tracker.on_connected();
        assert!(tracker.is_connected());
        assert!(!tracker.is_stale());
    }
}
