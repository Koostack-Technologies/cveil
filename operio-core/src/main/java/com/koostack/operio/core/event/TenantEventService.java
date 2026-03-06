package com.koostack.operio.core.event;

/**
 * Tenant-scoped event service SPI.
 *
 * @deprecated Use {@link EventService} instead. This interface is retained for backward
 *     compatibility and will be removed in a future release.
 */
@Deprecated(forRemoval = true)
public interface TenantEventService<T> extends EventService<T> {}
