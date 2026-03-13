package com.koostack.cveil.core.platform.core.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.core.events.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate root for the Tenant domain entity.
 *
 * <p>Lives in the platform-scoped event store. The {@link #tenantId()} always returns {@link
 * PlatformConstants#PLATFORM_TENANT_ID} for event routing; the {@link #id()} is the business
 * identifier of the managed tenant.
 */
public record TenantAggregate(
        String tenantId,
        UUID aggregateId,
        String id,
        String displayName,
        String plan,
        String tenantType,
        String status,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "tenant";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    /** Derive a deterministic aggregate ID from a tenant's business identifier. */
    public static UUID deriveId(String id) {
        return UUID.nameUUIDFromBytes(("tenant:" + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Apply a domain event to the current aggregate state, returning a new immutable instance. If
     * {@code current} is {@code null} the event is treated as the initial creation event.
     */
    public static TenantAggregate apply(
            TenantAggregate current, String eventType, JsonNode payloadNode, ObjectMapper mapper) {
        try {
            return switch (eventType) {
                case CoreEventTypes.TENANT_CREATED -> {
                    var e = mapper.treeToValue(payloadNode, TenantCreatedEvent.class);
                    yield new TenantAggregate(
                            PlatformConstants.PLATFORM_TENANT_ID,
                            deriveId(e.tenantId()),
                            e.tenantId(),
                            e.displayName(),
                            e.plan(),
                            e.tenantType(),
                            "created",
                            1L);
                }
                case CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED -> {
                    var e = mapper.treeToValue(payloadNode, TenantDisplayNameUpdatedEvent.class);
                    yield new TenantAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.id(),
                            e.displayName(),
                            current.plan(),
                            current.tenantType(),
                            current.status(),
                            current.version() + 1);
                }
                case CoreEventTypes.TENANT_PLAN_CHANGED -> {
                    var e = mapper.treeToValue(payloadNode, TenantPlanChangedEvent.class);
                    yield new TenantAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.id(),
                            current.displayName(),
                            e.newPlan(),
                            current.tenantType(),
                            current.status(),
                            current.version() + 1);
                }
                case CoreEventTypes.TENANT_SUSPENDED -> {
                    yield new TenantAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.id(),
                            current.displayName(),
                            current.plan(),
                            current.tenantType(),
                            "suspended",
                            current.version() + 1);
                }
                case CoreEventTypes.TENANT_ACTIVATED -> {
                    yield new TenantAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.id(),
                            current.displayName(),
                            current.plan(),
                            current.tenantType(),
                            "active",
                            current.version() + 1);
                }
                case CoreEventTypes.TENANT_DELETED -> {
                    yield new TenantAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.id(),
                            current.displayName(),
                            current.plan(),
                            current.tenantType(),
                            "deleted",
                            current.version() + 1);
                }
                default -> current;
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply event " + eventType, e);
        }
    }
}
