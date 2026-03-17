package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SdkStatus(
        boolean initialized,
        String connectionState,
        int graphNodes,
        int cachedDecisions,
        int decisionCacheCapacity) {}
