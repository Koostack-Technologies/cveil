package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.tenant.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link CategoryHydrationRepository}.
 */
public class JooqCategoryHydrationRepository implements CategoryHydrationRepository {

    private final EventService<?> eventService;

    public JooqCategoryHydrationRepository(EventService<?> eventService) {
        this.eventService = eventService;
    }

    @Override
    public CategoryAggregate findByName(String categoryName) {
        UUID id = CategoryAggregate.deriveId(categoryName);
        AggregateKey key = new AggregateKey(
            id, CategoryAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
        return eventService.getAggregate(key, CategoryAggregate.class);
    }
}
