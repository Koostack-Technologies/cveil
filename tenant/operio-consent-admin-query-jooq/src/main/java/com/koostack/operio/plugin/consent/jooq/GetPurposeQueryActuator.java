package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.PurposeView;

/** Query actuator that retrieves a single purpose by name. */
public class GetPurposeQueryActuator
        implements QueryActuator<GetPurposeQueryActuator.GetPurposeQuery, PurposeView> {

    private final PurposeRepository purposeRepository;
    private final ConsentAdminRecordMapper mapper;

    public GetPurposeQueryActuator(
            PurposeRepository purposeRepository, ConsentAdminRecordMapper mapper) {
        this.purposeRepository = purposeRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a single purpose.
     *
     * @param purposeName the purpose name to look up
     */
    public record GetPurposeQuery(String purposeName) {}

    @Override
    public PurposeView execute(GetPurposeQuery query) {
        return purposeRepository
                .findByName(query.purposeName())
                .map(mapper::toPurposeView)
                .orElse(null);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
