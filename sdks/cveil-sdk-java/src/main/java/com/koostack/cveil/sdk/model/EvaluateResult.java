package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluateResult(
        String field,
        @JsonProperty("masking_type") String maskingType,
        @JsonProperty("consent_action") String consentAction,
        @JsonProperty("needs_fetch") boolean needsFetch,
        @JsonProperty("policy_names") List<String> policyNames) {}
