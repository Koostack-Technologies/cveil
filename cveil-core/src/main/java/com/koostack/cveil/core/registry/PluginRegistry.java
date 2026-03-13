package com.koostack.cveil.core.registry;

import com.koostack.cveil.core.exception.CveilConfigurationException;
import com.koostack.cveil.core.spi.Configurable;
import com.koostack.cveil.core.spi.NamedPlugin;
import com.koostack.cveil.core.spi.Plugin;
import com.koostack.cveil.core.spi.PluginConfig;
import com.koostack.cveil.core.spi.Prioritized;
import java.util.Comparator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PluginRegistry {

    private static final Logger LOG = Logger.getLogger(
        PluginRegistry.class.getName()
    );

    /**
     * Resolves a plugin by its identifier (Short Name or FQN).
     * If the specific identifier isn't found, it falls back to the highest priority implementation.
     *
     * @param serviceClass The SPI interface class (e.g. ConsentService.class)
     * @param identifier   The preferred name (e.g. "okta" or "default")
     * @param <T>          The type of the plugin
     * @return The resolved plugin instance
     * @throws CveilConfigurationException if no implementations are found
     */
    private static <T extends NamedPlugin & Prioritized> T loadPlugin(
        Class<T> serviceClass,
        String identifier
    ) {
        // Load all available implementations via ServiceLoader
        ServiceLoader<T> loader = ServiceLoader.load(serviceClass);

        // Convert to a List/Stream for multiple passes
        var implementations = loader
            .stream()
            .map(ServiceLoader.Provider::get)
            .toList();

        if (implementations.isEmpty()) {
            throw new CveilConfigurationException(
                "No implementations found for SPI: " +
                    serviceClass.getName() +
                    ". Ensure the provider JAR includes a META-INF/services/" +
                    serviceClass.getName() +
                    " file."
            );
        }

        LOG.fine(
            () ->
                "ServiceLoader discovered " +
                implementations.size() +
                " implementation(s) for " +
                serviceClass.getName() +
                ": " +
                implementations
                    .stream()
                    .map(p -> p.getName() + " (" + p.getClass().getName() + ")")
                    .collect(Collectors.joining(", "))
        );

        // 1. Tug-of-War Side A: Exact Name Match
        // We check if the 'identifier' matches the friendly name OR the FQN.
        Optional<T> exactMatch = implementations
            .stream()
            .filter(
                p ->
                    p.getName().equalsIgnoreCase(identifier) ||
                    p.getClass().getName().equals(identifier)
            )
            .findFirst();

        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }

        // 2. Tug-of-War Side B: Priority Fallback
        // If the requested name wasn't found, we return the "strongest" plugin available.
        String available = implementations
            .stream()
            .map(p -> p.getName() + " (priority=" + p.getPriority() + ")")
            .collect(Collectors.joining(", "));
        LOG.warning(
            "No exact name match for identifier '" +
                identifier +
                "' in SPI " +
                serviceClass.getName() +
                "; falling back to highest-priority implementation. " +
                "Available: [" +
                available +
                "]. " +
                "Check that the intended plugin JAR includes a " +
                "META-INF/services/" +
                serviceClass.getName() +
                " file."
        );

        return implementations
            .stream()
            .max(Comparator.comparingInt(Prioritized::getPriority))
            .orElseThrow(() ->
                new CveilConfigurationException(
                    "Failed to resolve valid priority fallback for SPI: " +
                        serviceClass.getName()
                )
            );
    }

    @SuppressWarnings("unchecked")
    public static <T extends Plugin<C>, C extends PluginConfig> T resolve(
        Class<T> serviceClass,
        String identifier,
        C config
    ) {
        T plugin = loadPlugin(serviceClass, identifier);

        // Guard against config type mismatch (e.g. wrong SPI impl resolved
        // via priority fallback when the intended provider's service file
        // is missing). Due to type erasure the generic Configurable<C>
        // pattern match cannot catch this at compile time.
        if (plugin instanceof Configurable<?> configurable) {
            Class<?> expectedConfigType = configurable.getConfigurationType();
            if (!expectedConfigType.isInstance(config)) {
                throw new CveilConfigurationException(
                    "Configuration type mismatch for plugin '" +
                        plugin.getName() +
                        "' (" +
                        plugin.getClass().getName() +
                        "): expected config of type " +
                        expectedConfigType.getName() +
                        " but received " +
                        config.getClass().getName() +
                        ". This usually means the wrong SPI implementation was " +
                        "resolved — check that the intended plugin JAR includes " +
                        "a META-INF/services/" +
                        serviceClass.getName() +
                        " file."
                );
            }
            ((Configurable<C>) configurable).configure(config);
        }

        return plugin;
    }

    public static <T extends NamedPlugin & Prioritized> T resolve(
        Class<T> serviceClass,
        String identifier
    ) {
        return loadPlugin(serviceClass, identifier);
    }
}
