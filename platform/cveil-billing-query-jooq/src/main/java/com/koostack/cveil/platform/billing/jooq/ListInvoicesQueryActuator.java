package com.koostack.cveil.platform.billing.jooq;

import static com.koostack.cveil.platform.billing.jooq.BillingRecordMapper.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;

/** Query actuator that retrieves a paginated list of invoices for a tenant. */
public class ListInvoicesQueryActuator
        implements QueryActuator<ListInvoicesQueryActuator.ListInvoicesQuery, List<InvoiceInfo>> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public ListInvoicesQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    /**
     * @param page zero-based page index
     * @param pageSize number of invoices per page
     */
    public record ListInvoicesQuery(String tenantId, int page, int pageSize) {}

    @Override
    public List<InvoiceInfo> execute(ListInvoicesQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(INVOICE)
                                .where(INV_TENANT_ID.eq(query.tenantId()))
                                .orderBy(INV_CREATED_AT.desc())
                                .limit(query.pageSize())
                                .offset(query.page() * query.pageSize())
                                .fetch()
                                .map(BillingRecordMapper::toInvoiceInfo),
                retryConfig,
                "listInvoices(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
