package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishEventInput;
import com.koostack.operio.core.event.models.PublishTransformInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.spi.Plugin;

public interface TenantEventService<T> extends Plugin<T> {

    <U extends AggregateRoot> void publish(PublishEventInput<U> input);

    <U extends AggregateRoot> void publishTransform(PublishTransformInput<U> input);

    Long getAggregateVersion(AggregateKey key);

    <U> U getAggregate(AggregateKey key, Class<U> type);

    <U> void registerEventType(RegisterEventTypeInput<U> input);

}
