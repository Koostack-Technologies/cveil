package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.PurposeView;
import java.util.List;

/** Query actuator that lists all purposes with policies and regulation links. */
public class ListPurposesQueryActuator
        implements QueryActuator<ListPurposesQueryActuator.ListPurposesQuery, List<PurposeView>> {

    private final PurposeRepository purposeRepository;
    private final ConsentAdminRecordMapper mapper;

    public ListPurposesQueryActuator(
            PurposeRepository purposeRepository, ConsentAdminRecordMapper mapper) {
        this.purposeRepository = purposeRepository;
        this.mapper = mapper;
    }

    /** Query input for listing all purposes. */
    public record ListPurposesQuery() {}

    @Override
    public List<PurposeView> execute(ListPurposesQuery query) {
        return purposeRepository.findAll().stream().map(mapper::toPurposeView).toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
