package com.koostack.cveil.plugin.platform.stripe;

import com.koostack.cveil.core.spi.PluginConfig;

/**
 * Configuration for the Stripe payment processor plugin.
 *
 * @param secretKey the Stripe secret API key (e.g. {@code sk_test_xxx} or {@code sk_live_xxx})
 * @param publishableKey the Stripe publishable key (passed to frontend for Elements)
 * @param webhookSecret the Stripe webhook endpoint secret for signature verification
 */
public record StripePaymentProcessorConfig(
        String secretKey, String publishableKey, String webhookSecret) implements PluginConfig {}
