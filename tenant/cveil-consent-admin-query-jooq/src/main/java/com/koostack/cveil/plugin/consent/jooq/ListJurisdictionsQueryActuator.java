package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.JurisdictionView;
import java.util.List;

/** Query actuator that lists all jurisdictions. */
public class ListJurisdictionsQueryActuator
        implements QueryActuator<
                ListJurisdictionsQueryActuator.ListJurisdictionsQuery, List<JurisdictionView>> {

    private final JurisdictionRepository jurisdictionRepository;

    public ListJurisdictionsQueryActuator(JurisdictionRepository jurisdictionRepository) {
        this.jurisdictionRepository = jurisdictionRepository;
    }

    /** Query input for listing all jurisdictions. */
    public record ListJurisdictionsQuery() {}

    @Override
    public List<JurisdictionView> execute(ListJurisdictionsQuery query) {
        return jurisdictionRepository.findAll().stream()
                .map(
                        j ->
                                new JurisdictionView(
                                        j.getJurisdictionId(),
                                        j.getJurisdictionName(),
                                        j.getDescription()))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
