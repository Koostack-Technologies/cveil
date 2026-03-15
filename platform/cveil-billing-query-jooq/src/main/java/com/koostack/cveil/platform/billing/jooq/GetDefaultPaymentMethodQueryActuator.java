package com.koostack.cveil.platform.billing.jooq;

import static com.koostack.cveil.platform.billing.jooq.BillingRecordMapper.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Query actuator that retrieves the default payment method for a tenant. */
public class GetDefaultPaymentMethodQueryActuator
        implements QueryActuator<
                GetDefaultPaymentMethodQueryActuator.GetDefaultPaymentMethodQuery,
                PaymentMethodInfo> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public GetDefaultPaymentMethodQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    public record GetDefaultPaymentMethodQuery(String tenantId) {}

    @Override
    public PaymentMethodInfo execute(GetDefaultPaymentMethodQuery query) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(PAYMENT_METHOD)
                                    .where(PM_TENANT_ID.eq(query.tenantId()))
                                    .and(PM_IS_DEFAULT.eq(true))
                                    .and(PM_STATUS.eq("active"))
                                    .fetchOne();
                    return record != null ? BillingRecordMapper.toPaymentMethodInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getDefaultPaymentMethod(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
