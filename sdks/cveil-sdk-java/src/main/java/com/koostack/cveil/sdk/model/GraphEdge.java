package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphEdge(
        String sourceType,
        String sourceName,
        String targetType,
        String targetName,
        Map<String, String> properties) {}
