package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.event.PlatformEventService;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService the platform event service used to publish domain events
 */
public record ConsentAdminCommandConfig(
    PlatformEventService<?> eventService
) {}
