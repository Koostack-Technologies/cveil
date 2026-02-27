package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link CategoryHydrationRepository}.
 */
public class JooqCategoryHydrationRepository implements CategoryHydrationRepository {

    private final TenantEventService<?> eventService;

    public JooqCategoryHydrationRepository(TenantEventService<?> eventService) {
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
