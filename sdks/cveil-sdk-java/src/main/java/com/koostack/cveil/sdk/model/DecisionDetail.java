package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionDetail(String effectiveStatus, String resolvedBy, String expiresAt) {}
