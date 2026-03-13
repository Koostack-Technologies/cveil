package com.koostack.cveil.plugin.masking.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.spi.PluginConfig;

/**
 * Configuration for the Jackson masking plugin.
 *
 * @param objectMapper custom ObjectMapper instance, or {@code null} to use a
 *                     default mapper
 */
public record MaskingJacksonConfig(
    ObjectMapper objectMapper
) implements PluginConfig {
    /** Convenience constructor that uses a default ObjectMapper. */
    public MaskingJacksonConfig() {
        this(null);
    }
}
