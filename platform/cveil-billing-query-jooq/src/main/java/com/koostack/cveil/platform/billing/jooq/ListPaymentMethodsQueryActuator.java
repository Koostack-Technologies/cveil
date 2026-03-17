package com.koostack.cveil.platform.billing.jooq;

import static com.koostack.cveil.platform.billing.jooq.BillingRecordMapper.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;

/** Query actuator that retrieves all active payment methods for a tenant. */
public class ListPaymentMethodsQueryActuator
        implements QueryActuator<
                ListPaymentMethodsQueryActuator.ListPaymentMethodsQuery, List<PaymentMethodInfo>> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public ListPaymentMethodsQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    public record ListPaymentMethodsQuery(String tenantId) {}

    @Override
    public List<PaymentMethodInfo> execute(ListPaymentMethodsQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(PAYMENT_METHOD)
                                .where(PM_TENANT_ID.eq(query.tenantId()))
                                .and(PM_STATUS.eq("active"))
                                .orderBy(PM_IS_DEFAULT.desc(), PM_CREATED_AT.desc())
                                .fetch()
                                .map(BillingRecordMapper::toPaymentMethodInfo),
                retryConfig,
                "listPaymentMethods(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
