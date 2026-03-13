package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.platform.billing.BillingQueryService;
import com.koostack.operio.core.platform.billing.models.BillingProfileInfo;
import com.koostack.operio.core.platform.billing.models.SubscriptionInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Billing query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link BillingQueryService} interface (thin delegation layer for backward compatibility).
 */
public class BillingQueryDispatcher
        implements QueryDispatcher, BillingQueryService<JooqBillingQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(BillingQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private DSLContext dsl;
    private RetryConfig retryConfig = RetryableQuery.DEFAULT;

    // -- QueryDispatcher SPI --------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for query type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "billing-query-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqBillingQueryConfig> getConfigurationType() {
        return JooqBillingQueryConfig.class;
    }

    @Override
    public void configure(JooqBillingQueryConfig config) {
        this.dsl = config.dsl();

        actuators.put(
                GetBillingProfileQueryActuator.GetBillingProfileQuery.class,
                new GetBillingProfileQueryActuator(dsl, retryConfig));
        actuators.put(
                GetSubscriptionQueryActuator.GetSubscriptionQuery.class,
                new GetSubscriptionQueryActuator(dsl, retryConfig));
        actuators.put(
                ListBillingProfilesQueryActuator.ListBillingProfilesQuery.class,
                new ListBillingProfilesQueryActuator(dsl, retryConfig));

        LOG.info("BillingQueryDispatcher configured with {} actuators", actuators.size());
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    // -- BillingQueryService delegation (backward compatibility) ---------------

    @Override
    public BillingProfileInfo getBillingProfile(String tenantId) {
        return dispatch(new GetBillingProfileQueryActuator.GetBillingProfileQuery(tenantId));
    }

    @Override
    public SubscriptionInfo getSubscription(String tenantId) {
        return dispatch(new GetSubscriptionQueryActuator.GetSubscriptionQuery(tenantId));
    }

    @Override
    public List<BillingProfileInfo> listBillingProfiles() {
        return dispatch(new ListBillingProfilesQueryActuator.ListBillingProfilesQuery());
    }
}
