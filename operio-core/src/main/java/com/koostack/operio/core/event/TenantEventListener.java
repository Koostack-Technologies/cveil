package com.koostack.operio.core.event;

import com.koostack.operio.core.spi.Plugin;

/**
 * @deprecated Use {@link EventListener} instead.
 */
@Deprecated(forRemoval = true)
public interface TenantEventListener<T> extends EventListener<T> {

}
