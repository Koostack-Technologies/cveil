package com.koostack.operio.core.event;

import com.koostack.operio.core.spi.Plugin;

/**
 * Marker interface for event listener plugins that subscribe to domain events from an {@link
 * EventService}.
 *
 * <p>Replaces the now-deprecated {@link TenantEventListener} and {@link PlatformEventListener}
 * interfaces.
 *
 * @param <T> the configuration type for the listener implementation
 */
public interface EventListener<T> extends Plugin<T> {}
