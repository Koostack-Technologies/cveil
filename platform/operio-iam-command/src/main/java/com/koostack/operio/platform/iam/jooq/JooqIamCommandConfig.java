package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;

/**
 * Configuration for {@link JooqIamCommandService}.
 *
 * @param eventService the platform event service for publishing domain events
 */
public record JooqIamCommandConfig(
    EventService<?> eventService
) {
    public JooqIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
