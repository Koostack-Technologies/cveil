package com.koostack.cveil.core.event;

import com.koostack.cveil.core.spi.Plugin;

/**
 * Marker interface for event listener plugins that subscribe to domain events from an {@link
 * EventService}.
 *
 * @param <T> the configuration type for the listener implementation
 */
public interface EventListener<T> extends Plugin<T> {}
