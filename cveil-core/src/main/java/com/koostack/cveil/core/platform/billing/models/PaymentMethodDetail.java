package com.koostack.cveil.core.platform.billing.models;

/**
 * Processor-agnostic representation of a payment method.
 *
 * <p>Contains only non-sensitive display data — full card numbers and CVVs are never stored or
 * transmitted through our system.
 */
public record PaymentMethodDetail(
        String externalId,
        String type,
        String brand,
        String last4,
        int expMonth,
        int expYear,
        boolean isDefault) {}
