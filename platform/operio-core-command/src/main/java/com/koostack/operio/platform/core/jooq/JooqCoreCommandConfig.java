package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.event.EventService;

/**
 * Configuration for {@link JooqCoreDispatcher} (and legacy {@link JooqCoreCommandService}).
 *
 * @param eventService the platform event service for publishing domain events
 */
public record JooqCoreCommandConfig(EventService<?> eventService) {
    public JooqCoreCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
