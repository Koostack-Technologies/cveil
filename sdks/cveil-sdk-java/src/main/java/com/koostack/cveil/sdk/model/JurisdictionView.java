package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JurisdictionView(
        String jurisdictionId, String jurisdictionName, String description) {}
