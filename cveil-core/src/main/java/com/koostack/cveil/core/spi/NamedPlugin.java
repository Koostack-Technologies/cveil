package com.koostack.cveil.core.spi;

/**
 * Interface for any plugin that can be identified by a unique name.
 * Defaults to the Fully Qualified Name (FQN) to ensure collision safety.
 */
public interface NamedPlugin {
    /**
     * @return The unique identifier for this plugin. 
     * Defaults to the class name (e.g. "com.example.MyPlugin").
     * Users can override this to provide a friendly alias (e.g. "okta").
     */
    default String getName() {
        return this.getClass().getName();
    }
}