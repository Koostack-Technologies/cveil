package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.cqrs.Actuator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.platform.billing.BillingCommandService;
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
                new CreateSubscriptionCommandActuator(eventService, config.planQueryService()));
        actuators.put(
                ChangeSubscriptionPlanCommandActuator.ChangeSubscriptionPlanCommand.class,
                new ChangeSubscriptionPlanCommandActuator(eventService));
        actuators.put(
                CancelSubscriptionCommandActuator.CancelSubscriptionCommand.class,
                new CancelSubscriptionCommandActuator(eventService));
        actuators.put(
                RenewSubscriptionCommandActuator.RenewSubscriptionCommand.class,
                new RenewSubscriptionCommandActuator(eventService));

        // -- Payment method actuators --
        actuators.put(
                AttachPaymentMethodCommandActuator.AttachPaymentMethodCommand.class,
                new AttachPaymentMethodCommandActuator(eventService));
        actuators.put(
                DetachPaymentMethodCommandActuator.DetachPaymentMethodCommand.class,
                new DetachPaymentMethodCommandActuator(eventService));
        actuators.put(
                SetDefaultPaymentMethodCommandActuator.SetDefaultPaymentMethodCommand.class,
                new SetDefaultPaymentMethodCommandActuator(eventService));

        // -- Invoice actuators --
        actuators.put(
                RecordInvoiceCommandActuator.RecordInvoiceCommand.class,
                new RecordInvoiceCommandActuator(eventService));
        actuators.put(
                RecordInvoiceUpdatedCommandActuator.RecordInvoiceUpdatedCommand.class,
                new RecordInvoiceUpdatedCommandActuator(eventService));
        actuators.put(
                RecordInvoicePaidCommandActuator.RecordInvoicePaidCommand.class,
                new RecordInvoicePaidCommandActuator(eventService));

        // -- Usage reporting --
        actuators.put(
                RecordUsageReportedCommandActuator.RecordUsageReportedCommand.class,
                new RecordUsageReportedCommandActuator(eventService));

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
            String tenantId,
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            String externalSubscriptionId,
            Instant periodStart,
            Instant periodEnd) {
        dispatch(
                new CreateSubscriptionCommandActuator.CreateSubscriptionCommand(
                        tenantId, planTier, externalSubscriptionId, periodStart, periodEnd));
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

    // -- Payment method commands (new) ----------------------------------------

    @Override
    public void attachPaymentMethod(
            String tenantId,
            String externalPaymentMethodId,
            String type,
            String brand,
            String last4,
            int expMonth,
            int expYear,
            boolean isDefault) {
        dispatch(
                new AttachPaymentMethodCommandActuator.AttachPaymentMethodCommand(
                        tenantId,
                        externalPaymentMethodId,
                        type,
                        brand,
                        last4,
                        expMonth,
                        expYear,
                        isDefault));
    }

    @Override
    public void detachPaymentMethod(String tenantId, String externalPaymentMethodId) {
        dispatch(
                new DetachPaymentMethodCommandActuator.DetachPaymentMethodCommand(
                        tenantId, externalPaymentMethodId));
    }

    @Override
    public void setDefaultPaymentMethod(String tenantId, String externalPaymentMethodId) {
        dispatch(
                new SetDefaultPaymentMethodCommandActuator.SetDefaultPaymentMethodCommand(
                        tenantId, externalPaymentMethodId));
    }

    // -- Invoice commands (new) -----------------------------------------------

    @Override
    public void recordInvoice(
            String tenantId,
            String externalInvoiceId,
            String externalSubscriptionId,
            String status,
            String currency,
            long amountDue,
            long subtotal,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant periodStart,
            Instant periodEnd,
            Instant dueDate) {
        dispatch(
                new RecordInvoiceCommandActuator.RecordInvoiceCommand(
                        tenantId,
                        externalInvoiceId,
                        externalSubscriptionId,
                        status,
                        currency,
                        amountDue,
                        subtotal,
                        total,
                        hostedInvoiceUrl,
                        invoicePdfUrl,
                        periodStart,
                        periodEnd,
                        dueDate));
    }

    @Override
    public void recordInvoicePaid(
            String tenantId, String externalInvoiceId, long amountPaid, Instant paidAt) {
        dispatch(
                new RecordInvoicePaidCommandActuator.RecordInvoicePaidCommand(
                        tenantId, externalInvoiceId, amountPaid, paidAt));
    }

    @Override
    public void recordInvoiceUpdated(
            String tenantId,
            String externalInvoiceId,
            String status,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant paidAt) {
        dispatch(
                new RecordInvoiceUpdatedCommandActuator.RecordInvoiceUpdatedCommand(
                        tenantId,
                        externalInvoiceId,
                        status,
                        amountDue,
                        amountPaid,
                        amountRemaining,
                        total,
                        hostedInvoiceUrl,
                        invoicePdfUrl,
                        paidAt));
    }

    // -- Usage reporting (new) ------------------------------------------------

    @Override
    public void recordUsageReported(
            String tenantId, String metricName, long quantity, Instant reportedAt) {
        dispatch(
                new RecordUsageReportedCommandActuator.RecordUsageReportedCommand(
                        tenantId, metricName, quantity, reportedAt));
    }
}
