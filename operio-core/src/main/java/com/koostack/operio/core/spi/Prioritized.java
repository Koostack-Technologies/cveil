package com.koostack.operio.core.spi;

/**
 * Interface for plugins that require an execution order or fallback priority.
 */
public interface Prioritized {
    /**
     * @return The priority value. Higher values indicate higher priority.
     * Default is 0.
     */
    default int getPriority() {
        return 0;
    }
}