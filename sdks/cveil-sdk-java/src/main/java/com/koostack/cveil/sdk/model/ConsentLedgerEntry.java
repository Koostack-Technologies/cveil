package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsentLedgerEntry(
        int consentLedgerId,
        String subjectId,
        int policyId,
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        String action,
        String actor,
        String ipAddress,
        String userAgent,
        String createdAt,
        String expiresAt) {}
