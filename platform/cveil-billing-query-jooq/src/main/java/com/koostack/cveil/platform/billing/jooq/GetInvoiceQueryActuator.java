package com.koostack.cveil.platform.billing.jooq;

import static com.koostack.cveil.platform.billing.jooq.BillingRecordMapper.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceLineItemInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Query actuator that retrieves a single invoice with its line items. */
public class GetInvoiceQueryActuator
        implements QueryActuator<GetInvoiceQueryActuator.GetInvoiceQuery, InvoiceInfo> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public GetInvoiceQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    public record GetInvoiceQuery(String tenantId, String invoiceId) {}

    @Override
    public InvoiceInfo execute(GetInvoiceQuery query) {
        return RetryableQuery.withRetry(
                () -> {
                    Record invoiceRecord =
                            dsl.select()
                                    .from(INVOICE)
                                    .where(INV_TENANT_ID.eq(query.tenantId()))
                                    .and(INV_ID.eq(UUID.fromString(query.invoiceId())))
                                    .fetchOne();
                    if (invoiceRecord == null) {
                        return null;
                    }

                    UUID invoiceUuid = invoiceRecord.get(INV_ID);
                    List<InvoiceLineItemInfo> lineItems =
                            dsl.select()
                                    .from(INVOICE_LINE_ITEM)
                                    .where(ILI_INVOICE_ID.eq(invoiceUuid))
                                    .orderBy(ILI_CREATED_AT.asc())
                                    .fetch()
                                    .map(BillingRecordMapper::toLineItemInfo);

                    return BillingRecordMapper.toInvoiceInfo(invoiceRecord, lineItems);
                },
                result -> result == null,
                retryConfig,
                "getInvoice(" + query.tenantId() + ", " + query.invoiceId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
