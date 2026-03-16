package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldView(
        int fieldId,
        String fieldName,
        String description,
        Map<String, Object> entity,
        List<Map<String, Object>> categories) {}
