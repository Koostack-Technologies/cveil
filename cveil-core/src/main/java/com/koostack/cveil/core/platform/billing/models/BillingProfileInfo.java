package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;

/** Read-model DTO for billing profile information. */
public record BillingProfileInfo(
        String tenantId,
        String billingEmail,
        String taxId,
        String paymentProcessor,
        String paymentProcessorCustomerId,
        String paymentMethodStatus,
        Instant createdAt,
        Instant updatedAt) {}
