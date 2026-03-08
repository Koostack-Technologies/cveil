package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-based billing command dispatcher.
 *
 * <p>Implements both the generic {@link CommandDispatcher} SPI (actuator-based routing) and the
 * legacy {@link BillingCommandService} interface (thin delegation layer for backward compatibility
 * with existing REST resources and holders).
 *
 * <p>All non-deprecated command methods delegate to the dispatcher, which routes to the appropriate
 * {@link Actuator} by command record type.
 */
public class JooqBillingDispatcher
        implements CommandDispatcher, BillingCommandService<JooqBillingCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private EventService<?> eventService;

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-billing-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqBillingCommandConfig> getConfigurationType() {
        return JooqBillingCommandConfig.class;
    }

    @Override
    public void configure(JooqBillingCommandConfig config) {
        this.eventService = config.eventService();

        actuators.put(
                CreateBillingProfileCommandActuator.CreateBillingProfileCommand.class,
                new CreateBillingProfileCommandActuator(eventService));
        actuators.put(
                UpdateBillingEmailCommandActuator.UpdateBillingEmailCommand.class,
                new UpdateBillingEmailCommandActuator(eventService));
        actuators.put(
                UpdateTaxIdCommandActuator.UpdateTaxIdCommand.class,
                new UpdateTaxIdCommandActuator(eventService));
        actuators.put(
                SetPaymentProcessorCommandActuator.SetPaymentProcessorCommand.class,
                new SetPaymentProcessorCommandActuator(eventService));
        actuators.put(
                UpdatePaymentMethodStatusCommandActuator.UpdatePaymentMethodStatusCommand.class,
                new UpdatePaymentMethodStatusCommandActuator(eventService));
        actuators.put(
                CreateSubscriptionCommandActuator.CreateSubscriptionCommand.class,
                new CreateSubscriptionCommandActuator(eventService));
        actuators.put(
                ChangeSubscriptionPlanCommandActuator.ChangeSubscriptionPlanCommand.class,
                new ChangeSubscriptionPlanCommandActuator(eventService));
        actuators.put(
                CancelSubscriptionCommandActuator.CancelSubscriptionCommand.class,
                new CancelSubscriptionCommandActuator(eventService));
        actuators.put(
                RenewSubscriptionCommandActuator.RenewSubscriptionCommand.class,
                new RenewSubscriptionCommandActuator(eventService));

        LOG.info("JooqBillingDispatcher configured with {} actuators", actuators.size());
    }

    // -- BillingCommandService delegation (backward compatibility) -------------

    @Override
    public void createBillingProfile(String tenantId, String billingEmail) {
        dispatch(
                new CreateBillingProfileCommandActuator.CreateBillingProfileCommand(
                        tenantId, billingEmail));
    }

    @Override
    public void updateBillingEmail(String tenantId, String newEmail) {
        dispatch(
                new UpdateBillingEmailCommandActuator.UpdateBillingEmailCommand(
                        tenantId, newEmail));
    }

    @Override
    public void updateTaxId(String tenantId, String taxId) {
        dispatch(new UpdateTaxIdCommandActuator.UpdateTaxIdCommand(tenantId, taxId));
    }

    @Override
    public void setPaymentProcessor(String tenantId, String processor, String customerId) {
        dispatch(
                new SetPaymentProcessorCommandActuator.SetPaymentProcessorCommand(
                        tenantId, processor, customerId));
    }

    @Override
    public void updatePaymentMethodStatus(String tenantId, String status) {
        dispatch(
                new UpdatePaymentMethodStatusCommandActuator.UpdatePaymentMethodStatusCommand(
                        tenantId, status));
    }

    @Override
    public void createSubscription(
            String tenantId, String planTier, int evalLimit, int subjectLimit, int burstLimit) {
        dispatch(
                new CreateSubscriptionCommandActuator.CreateSubscriptionCommand(
                        tenantId, planTier));
    }

    @Override
    public void changeSubscriptionPlan(
            String tenantId,
            String oldPlanTier,
            String newPlanTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit) {
        dispatch(
                new ChangeSubscriptionPlanCommandActuator.ChangeSubscriptionPlanCommand(
                        tenantId, oldPlanTier, newPlanTier, evalLimit, subjectLimit, burstLimit));
    }

    @Override
    public void cancelSubscription(String tenantId) {
        dispatch(new CancelSubscriptionCommandActuator.CancelSubscriptionCommand(tenantId));
    }

    @Override
    public void renewSubscription(String tenantId, Instant periodStart, Instant periodEnd) {
        dispatch(
                new RenewSubscriptionCommandActuator.RenewSubscriptionCommand(
                        tenantId, periodStart, periodEnd));
    }
}
