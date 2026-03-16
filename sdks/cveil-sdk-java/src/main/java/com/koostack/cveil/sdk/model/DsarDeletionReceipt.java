package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DsarDeletionReceipt(
        String subject,
        String deletionRequestId,
        int ledgerEntriesRevoked,
        int cachedKeysDeleted,
        String status,
        String completedAt) {}
