package com.koostack.operio.core.event;

import com.koostack.operio.core.spi.Plugin;

/**
 * Marker interface for event listener plugins that subscribe to domain events from an {@link
 * EventService}.
 *
 * @param <T> the configuration type for the listener implementation
 */
public interface EventListener<T> extends Plugin<T> {}
