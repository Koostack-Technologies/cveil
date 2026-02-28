package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.tenant.consent.hydration.EntityHydrationRepository;
import com.koostack.operio.core.tenant.consent.hydration.PurposeHydrationRepository;
import com.koostack.operio.core.event.EventService;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService           the event service used to publish domain events
 * @param entityHydrationRepo    entity hydration for grant/revoke validation
 * @param categoryHydrationRepo  category hydration for grant/revoke validation
 * @param purposeHydrationRepo   purpose hydration for grant/revoke validation
 */
public record ConsentAdminCommandConfig(
    EventService<?> eventService,
    EntityHydrationRepository entityHydrationRepo,
    CategoryHydrationRepository categoryHydrationRepo,
    PurposeHydrationRepository purposeHydrationRepo
) {
    public ConsentAdminCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
        if (entityHydrationRepo == null || categoryHydrationRepo == null
                || purposeHydrationRepo == null) {
            throw new IllegalArgumentException("All hydration repos must be provided");
        }
    }
}
