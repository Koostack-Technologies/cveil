package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.event.models.TenantEvent;

import java.util.List;

/**
 * @deprecated Replaced by {@link AggregateEventApplier}. Kept only for
 *             compilation compatibility until the cleanup phase removes it.
 */
@Deprecated(forRemoval = true)
public final class AggregateHydrationUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private AggregateHydrationUtil() {}

    /**
     * @deprecated Use {@link AggregateEventApplier} directly.
     */
    @Deprecated(forRemoval = true)
    public static JsonNode hydrate(JsonNode baseState, List<TenantEvent> events) {
        if (events.isEmpty()) {
            return baseState;
        }
        // Delegate ignores baseState — callers should migrate to AggregateEventApplier
        return AggregateEventApplier.replayToJson(events);
    }

    public static JsonNode hydrate(List<TenantEvent> events) {
        return AggregateEventApplier.replayToJson(events);
    }
}
