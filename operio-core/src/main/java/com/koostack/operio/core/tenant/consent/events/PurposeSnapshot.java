package com.koostack.operio.core.tenant.consent.events;

import java.util.List;

/**
 * Snapshot of a Purpose aggregate's state after a mutation.
 */
public record PurposeSnapshot(
        String purposeName,
        String description,
        List<RegulationLinkSnapshot> regulations,
        List<PolicySnapshot> policies
) {

    public record RegulationLinkSnapshot(
            String regulationName,
            String lawfulBasis,
            String onGranted,
            String onDenied
    ) {}

    public record PolicySnapshot(
            String policyType,
            String fieldName,
            String entityName,
            String categoryName
    ) {}
}
