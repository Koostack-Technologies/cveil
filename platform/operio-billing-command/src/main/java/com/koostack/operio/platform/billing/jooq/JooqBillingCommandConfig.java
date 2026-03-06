package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.event.EventService;

/**
 * Configuration for {@link JooqBillingCommandService}.
 *
 * @param eventService the platform event service for publishing domain events
 */
public record JooqBillingCommandConfig(EventService<?> eventService) {
    public JooqBillingCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
