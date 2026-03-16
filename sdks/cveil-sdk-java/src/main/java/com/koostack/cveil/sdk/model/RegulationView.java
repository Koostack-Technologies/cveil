package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegulationView(
        int regulationId,
        String regulationName,
        String description,
        List<RegulationJurisdictionLink> activeJurisdictions) {}
