package com.koostack.operio.plugin.masking.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.spi.PluginConfig;

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
