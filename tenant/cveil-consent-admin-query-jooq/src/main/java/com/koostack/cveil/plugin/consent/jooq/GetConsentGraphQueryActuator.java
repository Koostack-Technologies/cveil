package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.cveil.core.tenant.consent.ConsentGraphQueryService;

/**
 * Query actuator that retrieves a paginated view of the consent graph. Delegates to the configured
 * {@link ConsentGraphQueryService} (typically Redis-backed).
 */
public class GetConsentGraphQueryActuator
        implements QueryActuator<GetConsentGraphQueryActuator.GetConsentGraphQuery, ConsentGraph> {

    private final ConsentGraphQueryService<?> graphService;

    public GetConsentGraphQueryActuator(ConsentGraphQueryService<?> graphService) {
        this.graphService = graphService;
    }

    /**
     * Query input for retrieving the consent graph.
     *
     * @param cursor opaque pagination cursor, or {@code null} for the first page
     * @param limit maximum number of nodes per page
     * @param rootType optional node type for BFS subgraph filtering
     * @param rootName optional node name for BFS subgraph filtering
     * @param depth optional BFS traversal depth limit
     */
    public record GetConsentGraphQuery(
            String cursor, int limit, String rootType, String rootName, Integer depth) {}

    @Override
    public ConsentGraph execute(GetConsentGraphQuery query) {
        return graphService.getConsentGraph(
                query.cursor(), query.limit(), query.rootType(), query.rootName(), query.depth());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
