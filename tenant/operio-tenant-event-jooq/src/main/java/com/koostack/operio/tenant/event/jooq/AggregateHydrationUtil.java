package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatchException;
import com.koostack.operio.core.event.models.TenantEvent;

import java.util.List;

/**
 * Shared utility for replaying {@link TenantEvent} patches onto a base state.
 */
public final class AggregateHydrationUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private AggregateHydrationUtil() {}

    /**
     * Replays the patches from the given events onto the provided base state,
     * returning the resulting {@link JsonNode}.
     */
    public static JsonNode hydrate(JsonNode baseState, List<TenantEvent> events)
            throws JsonPatchException {
        JsonNode state = baseState;
        for (TenantEvent event : events) {
            if (event.changes() != null) {
                state = event.changes().apply(state);
            }
        }
        return state;
    }

    /**
     * Replays the patches from the given events onto an empty JSON object.
     */
    public static JsonNode hydrate(List<TenantEvent> events) throws JsonPatchException {
        return hydrate(objectMapper.createObjectNode(), events);
    }
}
