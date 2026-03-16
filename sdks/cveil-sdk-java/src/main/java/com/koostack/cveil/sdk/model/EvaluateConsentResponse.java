package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluateConsentResponse(
        String subject,
        List<ConsentScope> granted,
        List<ConsentScope> denied,
        Map<String, String> reasons,
        Map<String, DecisionDetail> details) {}
