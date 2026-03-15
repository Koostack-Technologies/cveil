package com.koostack.cveil.core.platform.billing.models;

/**
 * Processor-agnostic representation of a setup intent for payment method collection.
 *
 * @param id the setup intent identifier (e.g. {@code seti_xxx} for Stripe)
 * @param clientSecret the client secret for frontend SDK (e.g. Stripe Elements)
 */
public record SetupIntentDetail(String id, String clientSecret) {}
