package com.koostack.cveil.plugin.consent.sync.embedded;

/**
 * Controls how the {@link CaffeineReconciliationHelper} synchronizes the
 * in-process Caffeine cache with the relational database (source of truth).
 *
 * <p>The choice of strategy determines whether stale or removed entries can
 * linger in the cache after reconciliation completes.
 *
 * @see CaffeineReconciliationHelper
 * @see ReconciliationResult
 */
public enum ReconciliationStrategy {

    /**
     * Clear the entire Caffeine cache (both consent entries and
     * field-category mappings) before repopulating from the DB.
     *
     * <p>This guarantees that the cache is an exact mirror of the DB after
     * reconciliation completes. The trade-off is a brief window during which
     * reads against the cache will see no data (a "read gap"). For
     * single-process embedded deployments this is typically acceptable and
     * is the <strong>recommended default</strong>.
     *
     * <h3>Behavior</h3>
     * <ol>
     *   <li>{@code CaffeineConsentRepository.invalidateAll()}</li>
     *   <li>{@code CaffeineFieldCategoryMappingRepository.clear()}</li>
     *   <li>Rebuild field-category mappings from the {@code field_category}
     *       table.</li>
     *   <li>Rebuild consent entries from the {@code consent_policy}
     *       table.</li>
     * </ol>
     */
    FULL_REBUILD,

    /**
     * Write DB state into the cache without clearing first.
     *
     * <p>Existing cache entries that are still present in the DB are
     * overwritten with the latest DB values. Entries that have been removed
     * or revoked in the DB but were previously cached <em>may linger</em>
     * until they are naturally evicted (by TTL or size-based eviction).
     *
     * <p>The advantage over {@link #FULL_REBUILD} is that there is no
     * read gap — the cache is never empty during reconciliation. This is
     * useful when temporary staleness is acceptable but a full cache miss
     * window is not.
     *
     * <h3>Behavior</h3>
     * <ol>
     *   <li>Rebuild field-category mappings from the {@code field_category}
     *       table (additive — existing mappings are preserved).</li>
     *   <li>Write consent entries from the {@code consent_policy} table
     *       (overwrites matching keys; stale keys may remain).</li>
     * </ol>
     */
    MERGE
}
