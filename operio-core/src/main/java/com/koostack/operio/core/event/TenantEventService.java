package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.spi.Plugin;

/**
 * Tenant-scoped event service SPI.
 *
 * @deprecated Use {@link EventService} instead. This interface is retained
 *             for backward compatibility and will be removed in a future
 *             release.
 */
@Deprecated(forRemoval = true)
public interface TenantEventService<T> extends EventService<T> {

}
