package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectView(
        String subjectId,
        String subjectName,
        String description,
        String createdAt,
        String createdBy,
        String updatedAt,
        String updatedBy) {}
