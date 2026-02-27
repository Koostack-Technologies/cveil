package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.data.consent.hydration.EntityHydrationRepository;
import com.koostack.operio.core.data.consent.hydration.PurposeHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService           the tenant event service used to publish domain events
 * @param entityHydrationRepo    optional — entity hydration for grant/revoke validation
 * @param categoryHydrationRepo  optional — category hydration for grant/revoke validation
 * @param purposeHydrationRepo   optional — purpose hydration for grant/revoke validation
 */
public record ConsentAdminCommandConfig(
    TenantEventService<?> eventService,
    EntityHydrationRepository entityHydrationRepo,
    CategoryHydrationRepository categoryHydrationRepo,
    PurposeHydrationRepository purposeHydrationRepo
) {
    /**
     * Backward-compatible constructor — no hydration repos (validation disabled).
     */
    public ConsentAdminCommandConfig(TenantEventService<?> eventService) {
        this(eventService, null, null, null);
    }
}
