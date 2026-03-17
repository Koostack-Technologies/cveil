package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;

/** Read-model DTO for a payment method mirror record. */
public record PaymentMethodInfo(
        String id,
        String tenantId,
        String externalPaymentMethodId,
        String type,
        String cardBrand,
        String cardLast4,
        int cardExpMonth,
        int cardExpYear,
        boolean isDefault,
        String status,
        Instant createdAt,
        Instant updatedAt) {}
