package com.koostack.cveil.plugin.consent.sync.embedded;

import java.time.Duration;

/**
 * Immutable snapshot of the outcome of a single reconciliation run.
 *
 * <p>Returned by every {@link CaffeineReconciliationHelper} reconciliation
 * method so that callers can log, meter, or assert on the results without
 * needing to inspect the cache directly.
 *
 * <h2>Counts</h2>
 * <ul>
 *   <li>{@link #mappingsLoaded} — number of field↔category links written
 *       to the {@code CaffeineFieldCategoryMappingRepository}.</li>
 *   <li>{@link #fieldPoliciesLoaded} — number of field-level consent
 *       entries written to the {@code CaffeineConsentRepository}.</li>
 *   <li>{@link #categoryPoliciesLoaded} — number of category-level consent
 *       policies processed. Each category policy fans out into one cache
 *       entry per mapped field, so the actual number of cache writes may
 *       be higher than this count.</li>
 *   <li>{@link #categoryFanOutEntries} — total number of individual cache
 *       entries produced by category fan-out. This equals the sum of
 *       {@code |fieldsForCategory(c)|} across all processed category
 *       policies.</li>
 *   <li>{@link #policiesSkipped} — number of policy rows that were skipped
 *       because the referenced field or category could not be resolved to
 *       a name (orphaned foreign key, deleted metadata, etc.).</li>
 *   <li>{@link #errors} — number of unexpected exceptions caught during
 *       individual entry processing. These are logged but do not abort the
 *       reconciliation run.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * <ul>
 *   <li>{@link #elapsed} — wall-clock duration of the reconciliation run,
 *       measured from the start of the first DB query to the end of the
 *       last cache write.</li>
 * </ul>
 *
 * <h2>Strategy</h2>
 * <ul>
 *   <li>{@link #strategy} — the {@link ReconciliationStrategy} that was
 *       used for this run.</li>
 * </ul>
 *
 * @param strategy              the reconciliation strategy used
 * @param mappingsLoaded        field↔category mappings written to the cache
 * @param fieldPoliciesLoaded   field-level consent entries written
 * @param categoryPoliciesLoaded category-level policies processed
 * @param categoryFanOutEntries individual cache entries produced by
 *                              category fan-out
 * @param policiesSkipped       policies skipped due to unresolvable
 *                              metadata
 * @param errors                unexpected exceptions caught (non-fatal)
 * @param elapsed               wall-clock duration of the reconciliation
 *
 * @see CaffeineReconciliationHelper
 * @see ReconciliationStrategy
 */
public record ReconciliationResult(
    ReconciliationStrategy strategy,
    int mappingsLoaded,
    int fieldPoliciesLoaded,
    int categoryPoliciesLoaded,
    int categoryFanOutEntries,
    int policiesSkipped,
    int errors,
    Duration elapsed
) {

    /**
     * Returns the total number of consent policy rows processed
     * (field-level + category-level), excluding skipped and errored rows.
     *
     * @return {@code fieldPoliciesLoaded + categoryPoliciesLoaded}
     */
    public int totalPoliciesLoaded() {
        return fieldPoliciesLoaded + categoryPoliciesLoaded;
    }

    /**
     * Returns the total number of individual cache entries written for
     * consent data: direct field-level entries plus the entries produced
     * by category fan-out.
     *
     * @return {@code fieldPoliciesLoaded + categoryFanOutEntries}
     */
    public int totalCacheEntriesWritten() {
        return fieldPoliciesLoaded + categoryFanOutEntries;
    }

    /**
     * Returns a concise human-readable summary suitable for logging.
     *
     * @return a summary string
     */
    public String summary() {
        return String.format(
            "Reconciliation[%s] completed in %dms — " +
                "mappings=%d, fieldPolicies=%d, categoryPolicies=%d " +
                "(fanOut=%d), skipped=%d, errors=%d, totalCacheWrites=%d",
            strategy,
            elapsed.toMillis(),
            mappingsLoaded,
            fieldPoliciesLoaded,
            categoryPoliciesLoaded,
            categoryFanOutEntries,
            policiesSkipped,
            errors,
            totalCacheEntriesWritten()
        );
    }

    // -----------------------------------------------------------------------
    // Builder (mutable accumulator used during reconciliation)
    // -----------------------------------------------------------------------

    /**
     * Mutable accumulator used internally by
     * {@link CaffeineReconciliationHelper} to build a
     * {@link ReconciliationResult} incrementally during a reconciliation
     * run.
     *
     * <p>Not part of the public API — callers receive only the immutable
     * {@link ReconciliationResult} record.
     */
    static final class Builder {

        private final ReconciliationStrategy strategy;
        private int mappingsLoaded;
        private int fieldPoliciesLoaded;
        private int categoryPoliciesLoaded;
        private int categoryFanOutEntries;
        private int policiesSkipped;
        private int errors;
        private final long startNanos;

        Builder(ReconciliationStrategy strategy) {
            this.strategy = strategy;
            this.startNanos = System.nanoTime();
        }

        Builder addMappingsLoaded(int count) {
            this.mappingsLoaded += count;
            return this;
        }

        Builder addFieldPolicy() {
            this.fieldPoliciesLoaded++;
            return this;
        }

        Builder addCategoryPolicy(int fanOutCount) {
            this.categoryPoliciesLoaded++;
            this.categoryFanOutEntries += fanOutCount;
            return this;
        }

        Builder addSkipped() {
            this.policiesSkipped++;
            return this;
        }

        Builder addError() {
            this.errors++;
            return this;
        }

        /**
         * Builds the immutable result, capturing the elapsed time from
         * construction of this builder to now.
         *
         * @return the reconciliation result
         */
        ReconciliationResult build() {
            Duration elapsed = Duration.ofNanos(
                System.nanoTime() - startNanos
            );
            return new ReconciliationResult(
                strategy,
                mappingsLoaded,
                fieldPoliciesLoaded,
                categoryPoliciesLoaded,
                categoryFanOutEntries,
                policiesSkipped,
                errors,
                elapsed
            );
        }
    }
}
