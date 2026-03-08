package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.RegulationView;

/**
 * Query actuator that retrieves a single regulation by name.
 */
public class GetRegulationQueryActuator
        implements QueryActuator<GetRegulationQueryActuator.GetRegulationQuery, RegulationView> {

    private final RegulationRepository regulationRepository;
    private final ConsentAdminRecordMapper mapper;

    public GetRegulationQueryActuator(
            RegulationRepository regulationRepository, ConsentAdminRecordMapper mapper) {
        this.regulationRepository = regulationRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a single regulation.
     *
     * @param regulationName the regulation name to look up
     */
    public record GetRegulationQuery(String regulationName) {}

    @Override
    public RegulationView execute(GetRegulationQuery query) {
        return regulationRepository
                .findByName(query.regulationName())
                .map(mapper::toRegulationView)
                .orElse(null);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
