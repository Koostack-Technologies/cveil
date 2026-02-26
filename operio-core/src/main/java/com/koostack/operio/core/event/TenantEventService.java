package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.spi.Plugin;

public interface TenantEventService<T> extends Plugin<T> {

    void publishEvent(PublishDomainEventInput input);

    Long getAggregateVersion(AggregateKey key);

    <U> U getAggregate(AggregateKey key, Class<U> type);

    <U> void registerEventType(RegisterEventTypeInput<U> input);

}
