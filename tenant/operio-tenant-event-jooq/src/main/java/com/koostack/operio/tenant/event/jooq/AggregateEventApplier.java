package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateRoot;

import java.util.List;

/**
 * Replays semantic domain events against tenant aggregates to rebuild state.
 *
 * <p>Replaces the old {@code AggregateHydrationUtil} which used JSON Patch.
 * Each aggregate type's static {@code apply()} method is dispatched based on
 * the stored {@code aggregateType} discriminator.
 */
public final class AggregateEventApplier {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private AggregateEventApplier() {}

    /**
     * Replays events from the beginning (no base state) and returns the
     * hydrated aggregate, or {@code null} if the event list is empty.
     */
    @SuppressWarnings("unchecked")
    public static <T extends AggregateRoot> T replay(
        List<TenantEvent> events, Class<T> type
    ) {
        if (events.isEmpty()) {
            return null;
        }
        String aggregateType = events.getFirst().aggregateType();
        T state = createDefault(events.getFirst(), type);
        for (TenantEvent event : events) {
            JsonNode payloadNode = parsePayload(event.payload());
            state = (T) applyEvent(state, aggregateType, event.eventType(), payloadNode);
        }
        return state;
    }

    /**
     * Replays events onto a pre-existing base state (e.g. from a snapshot).
     */
    @SuppressWarnings("unchecked")
    public static <T extends AggregateRoot> T replay(
        T baseState, List<TenantEvent> events
    ) {
        T state = baseState;
        String aggregateType = baseState.aggregateType();
        for (TenantEvent event : events) {
            JsonNode payloadNode = parsePayload(event.payload());
            state = (T) applyEvent(state, aggregateType, event.eventType(), payloadNode);
        }
        return state;
    }

    /**
     * Replays events returning the aggregate as a JsonNode (used by SnapshotWorker).
     */
    public static JsonNode replayToJson(List<TenantEvent> events) {
        if (events.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        String aggregateType = events.getFirst().aggregateType();
        Class<? extends AggregateRoot> type = aggregateClassFor(aggregateType);
        AggregateRoot state = replay(events, type);
        return objectMapper.valueToTree(state);
    }

    private static AggregateRoot applyEvent(
        AggregateRoot current,
        String aggregateType,
        String eventType,
        JsonNode payloadNode
    ) {
        return switch (aggregateType) {
            case ConsentPolicyAggregate.AGGREGATE_TYPE ->
                ConsentPolicyAggregate.apply(
                    (ConsentPolicyAggregate) current, eventType, payloadNode, objectMapper
                );
            case EntityAggregate.AGGREGATE_TYPE ->
                EntityAggregate.apply(
                    (EntityAggregate) current, eventType, payloadNode, objectMapper
                );
            case CategoryAggregate.AGGREGATE_TYPE ->
                CategoryAggregate.apply(
                    (CategoryAggregate) current, eventType, payloadNode, objectMapper
                );
            case PurposeAggregate.AGGREGATE_TYPE ->
                PurposeAggregate.apply(
                    (PurposeAggregate) current, eventType, payloadNode, objectMapper
                );
            case RegulationAggregate.AGGREGATE_TYPE ->
                RegulationAggregate.apply(
                    (RegulationAggregate) current, eventType, payloadNode, objectMapper
                );
            default -> throw new IllegalArgumentException(
                "Unknown aggregate type: " + aggregateType
            );
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends AggregateRoot> T createDefault(
        TenantEvent firstEvent, Class<T> type
    ) {
        String aggregateType = firstEvent.aggregateType();
        String tenantId = firstEvent.tenantId();
        return switch (aggregateType) {
            case ConsentPolicyAggregate.AGGREGATE_TYPE -> {
                JsonNode payload = parsePayload(firstEvent.payload());
                String subjectId = payload.has("subjectId")
                    ? payload.get("subjectId").asText()
                    : firstEvent.aggregateId().toString();
                yield (T) ConsentPolicyAggregate.create(tenantId, subjectId);
            }
            case EntityAggregate.AGGREGATE_TYPE -> {
                JsonNode payload = parsePayload(firstEvent.payload());
                String entityName = payload.has("entityName")
                    ? payload.get("entityName").asText()
                    : EntityAggregate.DEFAULT_ENTITY;
                yield (T) EntityAggregate.create(tenantId, entityName);
            }
            case CategoryAggregate.AGGREGATE_TYPE -> {
                JsonNode payload = parsePayload(firstEvent.payload());
                String categoryName = payload.has("categoryName")
                    ? payload.get("categoryName").asText() : "";
                String description = payload.has("description")
                    ? payload.get("description").asText() : null;
                yield (T) CategoryAggregate.create(tenantId, categoryName, description);
            }
            case PurposeAggregate.AGGREGATE_TYPE -> {
                JsonNode payload = parsePayload(firstEvent.payload());
                String purposeName = payload.has("purposeName")
                    ? payload.get("purposeName").asText() : "";
                String description = payload.has("description")
                    ? payload.get("description").asText() : null;
                yield (T) PurposeAggregate.create(tenantId, purposeName, description);
            }
            case RegulationAggregate.AGGREGATE_TYPE -> {
                JsonNode payload = parsePayload(firstEvent.payload());
                yield (T) RegulationAggregate.create(
                    tenantId,
                    payload.has("regulationName") ? payload.get("regulationName").asText() : "",
                    payload.has("fullName") ? payload.get("fullName").asText() : null,
                    payload.has("jurisdiction") ? payload.get("jurisdiction").asText() : null,
                    payload.has("description") ? payload.get("description").asText() : null,
                    payload.has("url") ? payload.get("url").asText() : null
                );
            }
            default -> throw new IllegalArgumentException(
                "Unknown aggregate type: " + aggregateType
            );
        };
    }

    private static Class<? extends AggregateRoot> aggregateClassFor(String aggregateType) {
        return switch (aggregateType) {
            case ConsentPolicyAggregate.AGGREGATE_TYPE -> ConsentPolicyAggregate.class;
            case EntityAggregate.AGGREGATE_TYPE -> EntityAggregate.class;
            case CategoryAggregate.AGGREGATE_TYPE -> CategoryAggregate.class;
            case PurposeAggregate.AGGREGATE_TYPE -> PurposeAggregate.class;
            case RegulationAggregate.AGGREGATE_TYPE -> RegulationAggregate.class;
            default -> throw new IllegalArgumentException(
                "Unknown aggregate type: " + aggregateType
            );
        };
    }

    private static JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse event payload", e);
        }
    }
}
