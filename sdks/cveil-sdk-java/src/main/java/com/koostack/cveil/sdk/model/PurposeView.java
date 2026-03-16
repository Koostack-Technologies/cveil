package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PurposeView(
        int purposeId,
        String purposeName,
        String description,
        List<PurposeRegulationLink> regulations,
        List<PurposePolicyView> policies) {}
