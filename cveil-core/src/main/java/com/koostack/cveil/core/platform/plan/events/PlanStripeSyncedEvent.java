package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Emitted when a plan's external payment-processor identifiers are synced.
 *
 * <p>Consumed by the core projection handler to persist the Stripe Product + Price IDs on the
 * {@code platform_core.plan} read model.
 */
public record PlanStripeSyncedEvent(String planId, String stripeProductId, String stripePriceId)
        implements DomainEvent {}
