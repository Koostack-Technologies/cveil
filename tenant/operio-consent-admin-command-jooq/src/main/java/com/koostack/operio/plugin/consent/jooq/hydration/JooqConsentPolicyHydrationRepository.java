package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.data.consent.hydration.ConsentPolicyHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link ConsentPolicyHydrationRepository}.
 */
public class JooqConsentPolicyHydrationRepository implements ConsentPolicyHydrationRepository {

    private final TenantEventService<?> eventService;

    public JooqConsentPolicyHydrationRepository(TenantEventService<?> eventService) {
        this.eventService = eventService;
    }

    @Override
    public ConsentPolicyAggregate findBySubject(String subjectId) {
        UUID id = ConsentPolicyAggregate.deriveId(subjectId);
        AggregateKey key = new AggregateKey(
            id, ConsentPolicyAggregate.AGGREGATE_TYPE, TenantContext.require()
        );
        return eventService.getAggregate(key, ConsentPolicyAggregate.class);
    }
}
