package com.koostack.operio.core.models;

import java.time.Instant;
import java.util.List;

public record SubjectConsentView(
    String subjectId,
    List<ConsentLedgerEntryView> entries
) {
    public record ConsentLedgerEntryView(
        long consentLedgerId,
        String subjectId,
        long policyId,
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
        Instant expiresAt
    ) {}
}
