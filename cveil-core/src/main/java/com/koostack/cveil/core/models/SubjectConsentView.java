package com.koostack.cveil.core.models;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubjectConsentView(String subjectId, List<ConsentLedgerEntryView> entries) {
    public record ConsentLedgerEntryView(
            UUID consentLedgerId,
            String subjectId,
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String action,
            String actor,
            String ipAddress,
            String userAgent,
            Instant createdAt,
            Instant expiresAt) {}
}
