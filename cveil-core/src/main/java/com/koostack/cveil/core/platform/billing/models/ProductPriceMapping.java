package com.koostack.cveil.core.platform.billing.models;

/**
 * Maps a local plan to its external product and price identifiers.
 *
 * <p>Returned by {@link
 * com.koostack.cveil.core.platform.billing.PaymentProcessorService#syncProduct}.
 */
public record ProductPriceMapping(String externalProductId, String externalPriceId) {}
