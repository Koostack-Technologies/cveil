package com.koostack.cveil.core.platform.billing;

import com.koostack.cveil.core.platform.billing.models.BillingProfileInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.spi.Plugin;
import java.util.List;

/**
 * Query service for the billing platform domain (billing read model).
 *
 * <p>Reads from the materialized {@code platform_billing.billing_profile} and {@code
 * platform_billing.subscription} tables populated by the {@link BillingProjection}.
 */
public interface BillingQueryService<T> extends Plugin<T> {

    /** Retrieve the billing profile for a tenant, or {@code null} if not found. */
    BillingProfileInfo getBillingProfile(String tenantId);

    /** Retrieve the current subscription for a tenant, or {@code null} if not found. */
    SubscriptionInfo getSubscription(String tenantId);

    /** List all billing profiles. */
    List<BillingProfileInfo> listBillingProfiles();
}
