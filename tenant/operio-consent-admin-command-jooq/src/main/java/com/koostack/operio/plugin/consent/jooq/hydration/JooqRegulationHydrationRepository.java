package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.data.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.data.consent.hydration.RegulationHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link RegulationHydrationRepository}.
 */
public class JooqRegulationHydrationRepository implements RegulationHydrationRepository {

    private final TenantEventService<?> eventService;

    public JooqRegulationHydrationRepository(TenantEventService<?> eventService) {
        this.eventService = eventService;
    }

    @Override
    public RegulationAggregate findByName(String regulationName) {
        UUID id = RegulationAggregate.deriveId(regulationName);
        AggregateKey key = new AggregateKey(
            id, RegulationAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
        return eventService.getAggregate(key, RegulationAggregate.class);
    }
}
