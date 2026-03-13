use std::collections::{HashMap, VecDeque};
#[allow(unused_imports)]
use crate::models::{CachedDecision, ConsentAction};

/// LRU + TTL cache for subject→policy consent decisions.
///
/// Keys are `"{subject}::{policy_name}"` (e.g., `"user-123::FIELD:User/email:Marketing"`).
/// Entries expire based on both TTL and LRU eviction when max size is reached.
pub struct DecisionCache {
    /// The actual cache entries.
    entries: HashMap<String, CachedDecision>,
    /// LRU tracking: most recently used at back, least recently used at front.
    lru_order: VecDeque<String>,
    /// Maximum number of entries.
    max_size: usize,
    /// TTL in milliseconds for cached decisions.
    ttl_ms: u64,
}

impl DecisionCache {
    pub fn new(max_size: usize, ttl_ms: u64) -> Self {
        Self {
            entries: HashMap::with_capacity(max_size.min(1024)),
            lru_order: VecDeque::with_capacity(max_size.min(1024)),
            max_size,
            ttl_ms,
        }
    }

    /// Build a cache key from subject and policy name.
    pub fn cache_key(subject: &str, policy_name: &str) -> String {
        format!("{subject}::{policy_name}")
    }

    /// Look up a cached decision. Returns `None` on miss or if expired.
    pub fn get(&mut self, key: &str, now_ms: u64) -> Option<&CachedDecision> {
        // Check if exists and not expired
        let expired = self
            .entries
            .get(key)
            .map(|d| d.is_expired(now_ms) || (now_ms - d.cached_at > self.ttl_ms))
            .unwrap_or(true);

        if expired {
            // Remove expired entry
            self.entries.remove(key);
            self.lru_order.retain(|k| k != key);
            return None;
        }

        // Promote to most recently used
        self.lru_order.retain(|k| k != key);
        self.lru_order.push_back(key.to_string());

        self.entries.get(key)
    }

    /// Insert or update a cached decision.
    pub fn put(&mut self, key: String, decision: CachedDecision) {
        // Remove old entry from LRU if it existed
        if self.entries.contains_key(&key) {
            self.lru_order.retain(|k| k != &key);
        }

        // Evict LRU entries if at capacity
        while self.entries.len() >= self.max_size && !self.lru_order.is_empty() {
            if let Some(evict_key) = self.lru_order.pop_front() {
                self.entries.remove(&evict_key);
            }
        }

        self.entries.insert(key.clone(), decision);
        self.lru_order.push_back(key);
    }

    /// Evict a specific entry by key.
    pub fn evict(&mut self, key: &str) -> bool {
        let removed = self.entries.remove(key).is_some();
        if removed {
            self.lru_order.retain(|k| k != key);
        }
        removed
    }

    /// Evict all entries for a given subject (all policies).
    pub fn evict_subject(&mut self, subject: &str) -> usize {
        let prefix = format!("{subject}::");
        let keys_to_remove: Vec<String> = self
            .entries
            .keys()
            .filter(|k| k.starts_with(&prefix))
            .cloned()
            .collect();

        let count = keys_to_remove.len();
        for key in &keys_to_remove {
            self.entries.remove(key);
        }
        self.lru_order.retain(|k| !k.starts_with(&prefix));
        count
    }

    /// Evict all entries whose policy name contains the given substring.
    /// Useful for flushing by field name, category, or purpose.
    pub fn evict_matching(&mut self, pattern: &str) -> usize {
        let keys_to_remove: Vec<String> = self
            .entries
            .keys()
            .filter(|k| k.contains(pattern))
            .cloned()
            .collect();

        let count = keys_to_remove.len();
        for key in &keys_to_remove {
            self.entries.remove(key);
        }
        self.lru_order
            .retain(|k| !k.contains(pattern));
        count
    }

    /// Flush the entire decision cache.
    pub fn flush(&mut self) -> usize {
        let count = self.entries.len();
        self.entries.clear();
        self.lru_order.clear();
        count
    }

    /// Number of cached entries.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Maximum capacity.
    pub fn max_size(&self) -> usize {
        self.max_size
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_decision(action: ConsentAction, cached_at: u64) -> CachedDecision {
        CachedDecision {
            action,
            expires_at: None,
            cached_at,
        }
    }

    fn make_expiring(action: ConsentAction, cached_at: u64, expires_at: u64) -> CachedDecision {
        CachedDecision {
            action,
            expires_at: Some(expires_at),
            cached_at,
        }
    }

    #[test]
    fn put_and_get() {
        let mut cache = DecisionCache::new(100, 300_000);
        let key = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(key.clone(), make_decision(ConsentAction::Grant, 1000));

        let dec = cache.get(&key, 1001).unwrap();
        assert!(dec.is_granted());
    }

    #[test]
    fn get_returns_none_on_miss() {
        let mut cache = DecisionCache::new(100, 300_000);
        assert!(cache.get("nonexistent", 1000).is_none());
    }

    #[test]
    fn get_returns_none_on_ttl_expiry() {
        let mut cache = DecisionCache::new(100, 1000); // 1s TTL
        let key = "user-1::policy-1".to_string();
        cache.put(key.clone(), make_decision(ConsentAction::Grant, 1000));

        // Within TTL
        assert!(cache.get(&key, 1500).is_some());

        // Past TTL
        assert!(cache.get(&key, 2001).is_none());
    }

    #[test]
    fn get_returns_none_on_decision_expiry() {
        let mut cache = DecisionCache::new(100, 300_000);
        let key = "user-1::policy-1".to_string();
        cache.put(key.clone(), make_expiring(ConsentAction::Grant, 1000, 5000));

        assert!(cache.get(&key, 4999).is_some());
        assert!(cache.get(&key, 5000).is_none());
    }

    #[test]
    fn lru_eviction() {
        let mut cache = DecisionCache::new(3, 300_000);

        cache.put("a".into(), make_decision(ConsentAction::Grant, 1));
        cache.put("b".into(), make_decision(ConsentAction::Grant, 2));
        cache.put("c".into(), make_decision(ConsentAction::Grant, 3));
        assert_eq!(cache.len(), 3);

        // Adding a 4th should evict "a" (least recently used)
        cache.put("d".into(), make_decision(ConsentAction::Grant, 4));
        assert_eq!(cache.len(), 3);
        assert!(cache.get("a", 10).is_none());
        assert!(cache.get("b", 10).is_some());
    }

    #[test]
    fn lru_access_promotes() {
        let mut cache = DecisionCache::new(3, 300_000);

        cache.put("a".into(), make_decision(ConsentAction::Grant, 1));
        cache.put("b".into(), make_decision(ConsentAction::Grant, 2));
        cache.put("c".into(), make_decision(ConsentAction::Grant, 3));

        // Access "a" to promote it
        cache.get("a", 10);

        // Now "b" is the LRU — adding "d" should evict "b"
        cache.put("d".into(), make_decision(ConsentAction::Grant, 4));
        assert!(cache.get("a", 10).is_some());
        assert!(cache.get("b", 10).is_none());
    }

    #[test]
    fn evict_specific() {
        let mut cache = DecisionCache::new(100, 300_000);
        cache.put("k1".into(), make_decision(ConsentAction::Grant, 1));
        assert!(cache.evict("k1"));
        assert!(!cache.evict("k1")); // Already gone
        assert!(cache.is_empty());
    }

    #[test]
    fn evict_subject() {
        let mut cache = DecisionCache::new(100, 300_000);
        let k1 = DecisionCache::cache_key("user-1", "policy-a");
        let k2 = DecisionCache::cache_key("user-1", "policy-b");
        let k3 = DecisionCache::cache_key("user-2", "policy-a");

        cache.put(k1, make_decision(ConsentAction::Grant, 1));
        cache.put(k2, make_decision(ConsentAction::Grant, 2));
        cache.put(k3, make_decision(ConsentAction::Grant, 3));

        let evicted = cache.evict_subject("user-1");
        assert_eq!(evicted, 2);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn evict_matching() {
        let mut cache = DecisionCache::new(100, 300_000);
        cache.put("u1::FIELD:User/email:Marketing".into(), make_decision(ConsentAction::Grant, 1));
        cache.put("u1::FIELD:User/phone:Marketing".into(), make_decision(ConsentAction::Grant, 2));
        cache.put("u2::FIELD:User/email:Analytics".into(), make_decision(ConsentAction::Grant, 3));

        // Evict all involving "User/email"
        let evicted = cache.evict_matching("User/email");
        assert_eq!(evicted, 2);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn flush() {
        let mut cache = DecisionCache::new(100, 300_000);
        cache.put("a".into(), make_decision(ConsentAction::Grant, 1));
        cache.put("b".into(), make_decision(ConsentAction::Grant, 2));

        let flushed = cache.flush();
        assert_eq!(flushed, 2);
        assert!(cache.is_empty());
    }

    #[test]
    fn cache_key_format() {
        assert_eq!(
            DecisionCache::cache_key("user-42", "FIELD:User/email:Marketing"),
            "user-42::FIELD:User/email:Marketing"
        );
    }
}
