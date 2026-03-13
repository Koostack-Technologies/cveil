package com.koostack.cveil.core.tenant.consent.invalidation;

/**
 * The type of mutation that triggered a cache invalidation.
 *
 * <p>Client SDKs use this to decide what to evict:
 *
 * <ul>
 *   <li>{@link #GRANTED} / {@link #REVOKED} — evict the specific consent entry.
 *   <li>{@link #ALL_REVOKED} — flush all entries for the subject.
 *   <li>{@link #CATEGORY_CHANGED} — refresh field↔category metadata (not consent decisions).
 *   <li>{@link #FIELD_REMOVED} — flush all entries for the affected field across all subjects.
 *   <li>{@link #ENTITY_REMOVED} — flush all entries for all fields of the affected entity across
 *       all subjects. The {@code entity} field identifies the deleted entity; {@code field} is
 *       {@code null}.
 *   <li>{@link #FIELD_CHANGED} — admin projection: a field aggregate was created, updated, or had
 *       categories changed.
 *   <li>{@link #PURPOSE_CHANGED} — admin projection: a purpose aggregate was created, updated, or
 *       had policies/regulations changed.
 *   <li>{@link #REGULATION_CHANGED} — admin projection: a regulation aggregate was created,
 *       updated, or had jurisdictions changed.
 *   <li>{@link #CONSENT_ENTRY_CHANGED} — admin projection: a subject consent ledger entry was
 *       granted, revoked, or expired.
 * </ul>
 */
public enum InvalidationAction {
    GRANTED,
    REVOKED,
    ALL_REVOKED,
    /** Field↔category mapping changed — clients re-fetch metadata. */
    CATEGORY_CHANGED,
    FIELD_REMOVED,
    /** Entire entity deleted — clients flush all fields belonging to this entity. */
    ENTITY_REMOVED,

    // Admin / graph projection invalidation actions
    /** Field aggregate mutated (registered, updated, removed, category assigned/unassigned). */
    FIELD_CHANGED,
    /**
     * Purpose aggregate mutated (registered, removed, regulation linked/unlinked, policy
     * added/removed).
     */
    PURPOSE_CHANGED,
    /** Regulation aggregate mutated (registered, removed, jurisdiction added/removed). */
    REGULATION_CHANGED,
    /** Subject consent ledger entry changed (granted, revoked, expired, all revoked). */
    CONSENT_ENTRY_CHANGED,

    // Graph node-type–specific invalidation actions
    /** Entity node created or orphaned (field registration creates entity implicitly). */
    ENTITY_CHANGED,
    /** Jurisdiction node created/removed (regulation↔jurisdiction edge mutation). */
    JURISDICTION_CHANGED,
    /** Policy node created/removed (purpose↔policy edge mutation). */
    POLICY_CHANGED,
    /** Subject node created (first consent grant materializes the subject). */
    SUBJECT_CHANGED
}
