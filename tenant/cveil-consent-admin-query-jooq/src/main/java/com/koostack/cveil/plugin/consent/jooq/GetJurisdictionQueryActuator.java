package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.JurisdictionView;
import java.util.Optional;

/** Query actuator that retrieves a single jurisdiction by name. */
public class GetJurisdictionQueryActuator
        implements QueryActuator<
                GetJurisdictionQueryActuator.GetJurisdictionQuery, Optional<JurisdictionView>> {

    private final JurisdictionRepository jurisdictionRepository;

    public GetJurisdictionQueryActuator(JurisdictionRepository jurisdictionRepository) {
        this.jurisdictionRepository = jurisdictionRepository;
    }

    /**
     * Query input for retrieving a single jurisdiction.
     *
     * @param jurisdictionName the jurisdiction name to look up
     */
    public record GetJurisdictionQuery(String jurisdictionName) {}

    @Override
    public Optional<JurisdictionView> execute(GetJurisdictionQuery query) {
        return jurisdictionRepository
                .findByName(query.jurisdictionName())
                .map(
                        j ->
                                new JurisdictionView(
                                        j.getJurisdictionId(),
                                        j.getJurisdictionName(),
                                        j.getDescription()));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
