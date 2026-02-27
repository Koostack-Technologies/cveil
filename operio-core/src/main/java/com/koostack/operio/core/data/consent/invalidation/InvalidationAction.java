package com.koostack.operio.core.data.consent.invalidation;

/**
 * The type of consent mutation that triggered a cache invalidation.
 *
 * <p>Client SDKs use this to decide what to evict:
 * <ul>
 *   <li>{@link #GRANTED} / {@link #REVOKED} — evict the specific consent entry.</li>
 *   <li>{@link #ALL_REVOKED} — flush all entries for the subject.</li>
 *   <li>{@link #CATEGORY_CHANGED} — refresh field↔category metadata (not consent decisions).</li>
 *   <li>{@link #FIELD_REMOVED} — flush all entries for the affected field across all subjects.</li>
 * </ul>
 */
public enum InvalidationAction {
    GRANTED,
    REVOKED,
    ALL_REVOKED,
    /** Field↔category mapping changed — clients re-fetch metadata. */
    CATEGORY_CHANGED,
    FIELD_REMOVED
}
