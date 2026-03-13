package com.koostack.operio.plugin.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.plan.aggregates.PlanAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectConsentAggregate;
import java.util.List;

/**
 * Replays semantic domain events against platform aggregates to rebuild state.
 *
 * <p>Each aggregate type's static {@code apply()} method is dispatched based on the stored {@code
 * aggregateType} discriminator.
 */
public final class PlatformAggregateEventApplier {

    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private PlatformAggregateEventApplier() {}

    /**
     * Replays events from the beginning (no base state) and returns the hydrated aggregate, or
     * {@code null} if the event list is empty.
     */
    @SuppressWarnings("unchecked")
    public static <T extends AggregateRoot> T replay(List<PlatformEvent> events, Class<T> type) {
        if (events.isEmpty()) {
            return null;
        }
        String aggregateType = events.getFirst().aggregateType();
        T state = null;
        for (PlatformEvent event : events) {
            JsonNode payloadNode = parsePayload(event.payload());
            state = (T) applyEvent(state, aggregateType, event.eventType(), payloadNode);
        }
        return state;
    }

    private static AggregateRoot applyEvent(
            AggregateRoot current, String aggregateType, String eventType, JsonNode payloadNode) {
        return switch (aggregateType) {
            case TenantAggregate.AGGREGATE_TYPE -> TenantAggregate.apply(
                    (TenantAggregate) current, eventType, payloadNode, objectMapper);
            case BillingProfileAggregate.AGGREGATE_TYPE -> BillingProfileAggregate.apply(
                    (BillingProfileAggregate) current, eventType, payloadNode, objectMapper);
            case PlanAggregate.AGGREGATE_TYPE -> PlanAggregate.apply(
                    (PlanAggregate) current, eventType, payloadNode, objectMapper);
            case FieldAggregate.AGGREGATE_TYPE -> FieldAggregate.apply(
                    (FieldAggregate) current, eventType, payloadNode, objectMapper);
            case PurposeAggregate.AGGREGATE_TYPE -> PurposeAggregate.apply(
                    (PurposeAggregate) current, eventType, payloadNode, objectMapper);
            case RegulationAggregate.AGGREGATE_TYPE -> RegulationAggregate.apply(
                    (RegulationAggregate) current, eventType, payloadNode, objectMapper);
            case SubjectConsentAggregate.AGGREGATE_TYPE -> SubjectConsentAggregate.apply(
                    (SubjectConsentAggregate) current, eventType, payloadNode, objectMapper);
            case DsarAggregate.AGGREGATE_TYPE -> DsarAggregate.apply(
                    (DsarAggregate) current, eventType, payloadNode, objectMapper);
            default -> throw new IllegalArgumentException(
                    "Unknown aggregate type: " + aggregateType);
        };
    }

    private static JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse event payload", e);
        }
    }
}
