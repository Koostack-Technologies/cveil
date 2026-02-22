package com.koostack.operio.plugin.resolver.sensitive.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.spi.PluginConfig;

/**
 * Configuration for the sensitive-annotation resolver plugin.
 *
 * @param objectMapper custom ObjectMapper instance for bean introspection,
 *                     or {@code null} to use a default mapper
 */
public record ResolverSensitiveAnnotationConfig(
    ObjectMapper objectMapper
) implements PluginConfig {
    /** Convenience constructor that uses a default ObjectMapper. */
    public ResolverSensitiveAnnotationConfig() {
        this(null);
    }
}
