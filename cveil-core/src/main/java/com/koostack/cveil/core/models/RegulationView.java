package com.koostack.cveil.core.models;

import java.util.List;
import java.util.UUID;

public record RegulationView(
        UUID regulationId,
        String regulationName,
        String description,
        List<JurisdictionView> activeJurisdictions) {
    public record JurisdictionView(UUID jurisdictionId, String jurisdictionName) {}
}
