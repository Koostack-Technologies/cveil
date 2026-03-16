package com.koostack.cveil.platform.plan.jooq;

import com.koostack.cveil.core.cqrs.Actuator;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.platform.plan.PlanPricingTier;
import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link PlanQueryService} interface (thin delegation layer for backward compatibility).
 */
public class PlanQueryDispatcher implements QueryDispatcher, PlanQueryService<JooqPlanQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(PlanQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private DSLContext dsl;
    private RetryConfig retryConfig = RetryableQuery.DEFAULT;

    private JooqPlanQueryService delegate;

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
        return "plan-query-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqPlanQueryConfig> getConfigurationType() {
        return JooqPlanQueryConfig.class;
    }

    @Override
    public void configure(JooqPlanQueryConfig config) {
        this.dsl = config.dsl();

        // Register query actuators
        actuators.put(
                ListPricingTiersQueryActuator.ListPricingTiersQuery.class,
                new ListPricingTiersQueryActuator(dsl, retryConfig));

        LOG.info("PlanQueryDispatcher configured with {} actuators", actuators.size());
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    // -- PlanQueryService delegation (backward compatibility) ------------------

    @Override
    public PlanInfo getPlan(String planId) {
        ensureDelegate();
        return delegate.getPlan(planId);
    }

    @Override
    public List<PlanInfo> listPlans() {
        ensureDelegate();
        return delegate.listPlans();
    }

    @Override
    public List<PlanInfo> listActivePlans() {
        ensureDelegate();
        return delegate.listActivePlans();
    }

    @Override
    public List<PlanPricingTier> listPricingTiers() {
        return dispatch(new ListPricingTiersQueryActuator.ListPricingTiersQuery());
    }

    private void ensureDelegate() {
        if (delegate == null) {
            delegate = new JooqPlanQueryService();
            delegate.configure(new JooqPlanQueryConfig(dsl));
            delegate.setRetryConfig(retryConfig);
        }
    }
}
