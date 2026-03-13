package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.plan.PlanQueryService;

/**
 * Configuration for {@link JooqBillingDispatcher} (and legacy {@link JooqBillingCommandService}).
 *
 * @param eventService the platform event service for publishing domain events
 * @param planQueryService the plan query service for validating plans during subscription creation
 */
public record JooqBillingCommandConfig(
        EventService<?> eventService, PlanQueryService<?> planQueryService) {
    public JooqBillingCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
        if (planQueryService == null) {
            throw new IllegalArgumentException("planQueryService must not be null");
        }
    }
}
