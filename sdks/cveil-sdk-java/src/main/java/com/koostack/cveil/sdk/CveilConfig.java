package com.koostack.cveil.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SDK configuration — matches the Rust SdkConfig (camelCase JSON).
 *
 * @param apiBaseUrl Base URL of the cveil REST API
 * @param apiToken API token — must start with "cveil_"
 * @param defaultJurisdiction Default jurisdiction for masking resolution (e.g., "EU")
 * @param staleMode Behavior when SSE is disconnected beyond threshold
 * @param disconnectThresholdMs Milliseconds before stale mode kicks in (default: 60000)
 * @param decisionCacheMaxSize Max cached consent decisions (default: 10000)
 * @param decisionCacheTtlMs TTL for cached decisions in ms (default: 300000)
 * @param dedupWindowSize Dedup ring buffer size (default: 500)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CveilConfig(
        String apiBaseUrl,
        String apiToken,
        String defaultJurisdiction,
        String staleMode,
        Long disconnectThresholdMs,
        Integer decisionCacheMaxSize,
        Long decisionCacheTtlMs,
        Integer dedupWindowSize) {

    /** Convenience constructor with required fields only. */
    public CveilConfig(String apiBaseUrl, String apiToken, String defaultJurisdiction) {
        this(apiBaseUrl, apiToken, defaultJurisdiction, null, null, null, null, null);
    }
}
