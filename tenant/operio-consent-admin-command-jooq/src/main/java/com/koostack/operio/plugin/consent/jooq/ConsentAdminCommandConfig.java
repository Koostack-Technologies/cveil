package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.event.TenantEventService;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService the tenant event service used to publish domain events
 */
public record ConsentAdminCommandConfig(
    TenantEventService<?> eventService
) {}
