package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.RegulationView;
import java.util.List;

/**
 * Query actuator that lists all regulations with jurisdictions.
 */
public class ListRegulationsQueryActuator
        implements QueryActuator<
                ListRegulationsQueryActuator.ListRegulationsQuery, List<RegulationView>> {

    private final RegulationRepository regulationRepository;
    private final ConsentAdminRecordMapper mapper;

    public ListRegulationsQueryActuator(
            RegulationRepository regulationRepository, ConsentAdminRecordMapper mapper) {
        this.regulationRepository = regulationRepository;
        this.mapper = mapper;
    }

    /** Query input for listing all regulations. */
    public record ListRegulationsQuery() {}

    @Override
    public List<RegulationView> execute(ListRegulationsQuery query) {
        return regulationRepository.findAll().stream().map(mapper::toRegulationView).toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
