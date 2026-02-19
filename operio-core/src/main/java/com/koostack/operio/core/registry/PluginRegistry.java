package com.koostack.operio.core.registry;

import com.koostack.operio.core.spi.Configurable;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.spi.PluginConfig;
import com.koostack.operio.core.spi.Prioritized;

import java.util.Comparator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Stream;

public class PluginRegistry {

    /**
     * Resolves a plugin by its identifier (Short Name or FQN).
     * If the specific identifier isn't found, it falls back to the highest priority implementation.
     *
     * @param serviceClass The SPI interface class (e.g. ConsentService.class)
     * @param identifier   The preferred name (e.g. "okta" or "default")
     * @param <T>          The type of the plugin
     * @return The resolved plugin instance
     * @throws IllegalStateException if no implementations are found
     */
    private static <T extends NamedPlugin & Prioritized> T loadPlugin(Class<T> serviceClass, String identifier) {
        // Load all available implementations via ServiceLoader
        ServiceLoader<T> loader = ServiceLoader.load(serviceClass);

        // Convert to a List/Stream for multiple passes
        var implementations = loader.stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        if (implementations.isEmpty()) {
            throw new IllegalStateException("No implementations found for SPI: " + serviceClass.getName());
        }

        // 1. Tug-of-War Side A: Exact Name Match
        // We check if the 'identifier' matches the friendly name OR the FQN.
        Optional<T> exactMatch = implementations.stream()
                .filter(p -> p.getName().equalsIgnoreCase(identifier) || p.getClass().getName().equals(identifier))
                .findFirst();

        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }


        // 2. Tug-of-War Side B: Priority Fallback
        // If the requested name wasn't found, we return the "strongest" plugin available.
        return implementations.stream()
                .max(Comparator.comparingInt(Prioritized::getPriority))
                .orElseThrow(() -> new IllegalStateException("Failed to resolve valid priority fallback."));
    }

        public static <T extends Plugin<C>, C extends PluginConfig> T resolve(
                Class<T> serviceClass,
                String identifier,
                C config) {
                    
                    T plugin = loadPlugin(serviceClass, identifier);
        
                    // If this plugin needs configuration, inject it
                    if (plugin instanceof Configurable<C> configurable) {
                        configurable.configure(config);
                    }

            return plugin;
        }
        
        public static <T extends NamedPlugin & Prioritized> T resolve(Class<T> serviceClass,
        String identifier) {
            return loadPlugin(serviceClass, identifier);
        }


}
