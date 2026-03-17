package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectDataExport(
        String subject, SubjectConsentView consent, int cachedKeyCount, String exportedAt) {}
