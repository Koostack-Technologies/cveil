package com.koostack.cveil.core.platform.plan.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import com.koostack.cveil.core.platform.plan.events.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate root for the Plan domain entity.
 *
 * <p>Lives in the platform-scoped event store. Represents a subscription plan definition (e.g.
 * {@code "free"}, {@code "pro"}, {@code "enterprise"}) with its associated rate limits. The {@link
 * #tenantId()} always returns {@link PlatformConstants#PLATFORM_TENANT_ID} for event routing.
 */
public record PlanAggregate(
        String tenantId,
        UUID aggregateId,
        String planId,
        String displayName,
        String description,
        long evalLimit,
        long subjectLimit,
        int burstLimit,
        int memberLimit,
        int auditRetentionDays,
        boolean isPublic,
        boolean isActive,
        int sortOrder,
        String stripeProductId,
        String stripePriceId,
        String stripeAnnualPriceId,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "plan";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    /** Derive a deterministic aggregate ID from the plan's business identifier. */
    public static UUID deriveId(String planId) {
        return UUID.nameUUIDFromBytes(("plan:" + planId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Apply a domain event to the current aggregate state, returning a new immutable instance. If
     * {@code current} is {@code null} the event is treated as the initial creation event.
     */
    public static PlanAggregate apply(
            PlanAggregate current, String eventType, JsonNode payloadNode, ObjectMapper mapper) {
        try {
            return switch (eventType) {
                case PlanEventTypes.PLAN_CREATED -> {
                    var e = mapper.treeToValue(payloadNode, PlanCreatedEvent.class);
                    yield new PlanAggregate(
                            PlatformConstants.PLATFORM_TENANT_ID,
                            deriveId(e.planId()),
                            e.planId(),
                            e.displayName(),
                            e.description(),
                            e.evalLimit(),
                            e.subjectLimit(),
                            e.burstLimit(),
                            -1, // memberLimit — default unlimited for event-created plans
                            30, // auditRetentionDays — default 30
                            e.isPublic(),
                            e.isActive(),
                            e.sortOrder(),
                            null,
                            null,
                            null,
                            1L);
                }
                case PlanEventTypes.PLAN_UPDATED -> {
                    var e = mapper.treeToValue(payloadNode, PlanUpdatedEvent.class);
                    yield new PlanAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.planId(),
                            e.displayName(),
                            e.description(),
                            e.evalLimit(),
                            e.subjectLimit(),
                            e.burstLimit(),
                            current.memberLimit(),
                            current.auditRetentionDays(),
                            e.isPublic(),
                            current.isActive(),
                            e.sortOrder(),
                            current.stripeProductId(),
                            current.stripePriceId(),
                            current.stripeAnnualPriceId(),
                            current.version() + 1);
                }
                case PlanEventTypes.PLAN_DEACTIVATED -> {
                    yield new PlanAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.planId(),
                            current.displayName(),
                            current.description(),
                            current.evalLimit(),
                            current.subjectLimit(),
                            current.burstLimit(),
                            current.memberLimit(),
                            current.auditRetentionDays(),
                            current.isPublic(),
                            false,
                            current.sortOrder(),
                            current.stripeProductId(),
                            current.stripePriceId(),
                            current.stripeAnnualPriceId(),
                            current.version() + 1);
                }
                case PlanEventTypes.PLAN_ACTIVATED -> {
                    yield new PlanAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.planId(),
                            current.displayName(),
                            current.description(),
                            current.evalLimit(),
                            current.subjectLimit(),
                            current.burstLimit(),
                            current.memberLimit(),
                            current.auditRetentionDays(),
                            current.isPublic(),
                            true,
                            current.sortOrder(),
                            current.stripeProductId(),
                            current.stripePriceId(),
                            current.stripeAnnualPriceId(),
                            current.version() + 1);
                }
                case PlanEventTypes.PLAN_STRIPE_SYNCED -> {
                    var e = mapper.treeToValue(payloadNode, PlanStripeSyncedEvent.class);
                    yield new PlanAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.planId(),
                            current.displayName(),
                            current.description(),
                            current.evalLimit(),
                            current.subjectLimit(),
                            current.burstLimit(),
                            current.memberLimit(),
                            current.auditRetentionDays(),
                            current.isPublic(),
                            current.isActive(),
                            current.sortOrder(),
                            e.stripeProductId(),
                            e.stripePriceId(),
                            e.stripeAnnualPriceId() != null
                                    ? e.stripeAnnualPriceId()
                                    : current.stripeAnnualPriceId(),
                            current.version() + 1);
                }
                default -> current;
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply event " + eventType, e);
        }
    }
}
