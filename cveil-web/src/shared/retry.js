/**
 * Retry-until-changed helper for CQRS eventual consistency.
 *
 * After a command (POST/PUT/DELETE), the read model may be stale.
 * This calls fetchFn repeatedly with exponential backoff until the
 * result differs from the previous snapshot.
 */

const DEFAULTS = { maxAttempts: 5, initialDelay: 60, maxDelay: 500, backoff: 2 };

export async function retryUntilChanged(fetchFn, previousSnapshot, opts = {}) {
    const { maxAttempts, initialDelay, maxDelay, backoff } = { ...DEFAULTS, ...opts };
    const prev = JSON.stringify(previousSnapshot);
    let delay = initialDelay;

    for (let i = 0; i < maxAttempts; i++) {
        await new Promise(r => setTimeout(r, delay));
        try {
            const result = await fetchFn();
            if (JSON.stringify(result) !== prev) return result;
        } catch (_) {
            // read model may briefly error during projection — keep retrying
        }
        delay = Math.min(delay * backoff, maxDelay);
    }

    // exhausted retries — return latest fetch as best effort
    try { return await fetchFn(); } catch (_) { return previousSnapshot; }
}

/**
 * Mark a table body as loading (reduced opacity) during retry polling.
 */
export function setTableLoading(tableId, loading) {
    const el = document.getElementById(tableId);
    if (!el) return;
    el.style.opacity = loading ? '0.45' : '';
    el.style.pointerEvents = loading ? 'none' : '';
    el.style.transition = 'opacity 0.15s ease';
}
