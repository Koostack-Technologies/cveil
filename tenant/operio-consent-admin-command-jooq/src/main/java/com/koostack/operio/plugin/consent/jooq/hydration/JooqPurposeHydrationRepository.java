package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.data.consent.hydration.PurposeHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link PurposeHydrationRepository}.
 */
public class JooqPurposeHydrationRepository implements PurposeHydrationRepository {

    private final TenantEventService<?> eventService;

    public JooqPurposeHydrationRepository(TenantEventService<?> eventService) {
        this.eventService = eventService;
    }

    @Override
    public PurposeAggregate findByName(String purposeName) {
        UUID id = PurposeAggregate.deriveId(purposeName);
        AggregateKey key = new AggregateKey(
            id, PurposeAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
        return eventService.getAggregate(key, PurposeAggregate.class);
    }
}
