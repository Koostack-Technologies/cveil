package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.hydration.EntityHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.tenant.event.jooq.EventRepository;
import java.util.List;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link EntityHydrationRepository}.
 *
 * <p>Hydrates {@link EntityAggregate} from the event store by deriving
 * the aggregate ID from the entity name and replaying events.
 */
public class JooqEntityHydrationRepository implements EntityHydrationRepository {

    private final TenantEventService<?> eventService;
    private final EventRepository eventRepository;

    public JooqEntityHydrationRepository(
        TenantEventService<?> eventService,
        EventRepository eventRepository
    ) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
    }

    @Override
    public EntityAggregate findByName(String entityName) {
        UUID id = EntityAggregate.deriveId(entityName);
        AggregateKey key = new AggregateKey(
            id, EntityAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
        return eventService.getAggregate(key, EntityAggregate.class);
    }

    @Override
    public List<UUID> listEntityAggregateIds() {
        return eventRepository.listAggregateIds(
            EntityAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
    }
}
