package com.koostack.cveil.core.models;

import java.util.List;

public record RegulationView(
        long regulationId,
        String regulationName,
        String description,
        List<JurisdictionView> activeJurisdictions) {
    public record JurisdictionView(long jurisdictionId, String jurisdictionName) {}
}
