package com.koostack.cveil.core.tenant.consent.events;

import java.util.List;

/**
 * Snapshot of a Field aggregate's state after a mutation, carried in events so projections can
 * overwrite directly without read-modify-write.
 */
public record FieldSnapshot(
        String fieldName, String entityName, String description, List<String> categoryNames) {}
