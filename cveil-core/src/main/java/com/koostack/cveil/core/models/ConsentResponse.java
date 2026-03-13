package com.koostack.cveil.core.models;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
/**
 * The result of evaluating consent for a subject against a set of
 * {@link ConsentScope}s.
 *
 * @param subject the subject whose consent was evaluated
 * @param granted the set of scopes that were granted
 * @param denied  the set of scopes that were denied
 * @param reasons human-readable denial reasons keyed by field name
 *                (backward-compatible; prefer {@link #details} for
 *                structured audit data)
 * @param details per-scope structured audit information describing how
 *                each decision was resolved — includes the resolution
 *                level ({@code FIELD}, {@code CATEGORY}, {@code NONE})
 *                and any expiration timestamp
 */
public record ConsentResponse(
    String subject,
    Set<ConsentScope> granted,
    Set<ConsentScope> denied,
    Map<String, String> reasons,
    Map<ConsentScope, DecisionDetail> details
) {
    /**
     * Full factory method including structured decision details.
     */
    public static ConsentResponse to(
        String subject,
        Set<ConsentScope> granted,
        Set<ConsentScope> denied,
        Map<String, String> reasons,
        Map<ConsentScope, DecisionDetail> details
    ) {
        return new ConsentResponse(
            subject,
            granted,
            denied,
            reasons,
            details
        );
    }

    /**
     * Backward-compatible factory method — creates a response with an
     * empty {@code details} map. Existing callers that do not yet
     * populate {@link DecisionDetail}s continue to work unchanged.
     */
    public static ConsentResponse to(
        String subject,
        Set<ConsentScope> granted,
        Set<ConsentScope> denied,
        Map<String, String> reasons
    ) {
        return new ConsentResponse(
            subject,
            granted,
            denied,
            reasons,
            Collections.emptyMap()
        );
    }
}
