package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.event.EventService;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService the event service used to publish domain events
 */
public record ConsentAdminCommandConfig(EventService<?> eventService) {
    public ConsentAdminCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
