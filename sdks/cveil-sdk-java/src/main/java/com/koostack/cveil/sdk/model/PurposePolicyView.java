package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PurposePolicyView(
        int policyId, String policyType, Map<String, Object> category, Map<String, Object> field) {}
