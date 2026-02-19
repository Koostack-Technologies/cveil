package com.koostack.operio.core.spi;

/**
 * Optional interface for plugins that support configuration after instantiation.
 * Implement this to allow setup arguments via the configure method.
 */
public interface Configurable<T> {
    Class<T> getConfigurationType();
    void configure(T config);
}
