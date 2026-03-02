package com.koostack.operio.core.tenant.consent.events;

import java.util.List;

/**
 * Snapshot of a Regulation aggregate's state after a mutation.
 */
public record RegulationSnapshot(
        String regulationName,
        String description,
        List<String> jurisdictionNames
) {}
