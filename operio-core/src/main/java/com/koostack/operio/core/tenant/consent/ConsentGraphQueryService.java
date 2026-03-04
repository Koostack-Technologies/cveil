package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.operio.core.spi.Plugin;

/**
 * SPI for querying the tenant consent graph.
 *
 * <p>The consent graph is a materialized view of all consent-related
 * entities and their relationships stored as a property graph in Redis.
 * This service exposes paginated graph traversal for admin dashboards,
 * compliance reporting, and topology exploration.
 *
 * <p>Implementations are discovered via
 * {@code META-INF/services/com.koostack.operio.core.tenant.consent.ConsentGraphQueryService}.
 *
 * @param <T> the configuration type for this plugin
 */
public interface ConsentGraphQueryService<T> extends Plugin<T> {

    /**
     * Returns a paginated view of the tenant's consent graph.
     *
     * <p>When {@code rootType} and {@code rootName} are provided, performs a
     * breadth-first traversal from the specified root node up to
     * {@code depth} hops, returning only the reachable subgraph.
     *
     * <p>When no root is specified, paginates across all nodes ordered by
     * type and name using cursor-based pagination.
     *
     * @param cursor   opaque pagination cursor from a previous response,
     *                 or {@code null} for the first page
     * @param limit    maximum number of nodes to return per page
     * @param rootType optional node type to start traversal from
     *                 (e.g. {@code "purpose"}, {@code "subject"})
     * @param rootName optional node name to start traversal from
     *                 (e.g. {@code "Marketing"}, {@code "subj-123"})
     * @param depth    maximum traversal depth from the root node, or
     *                 {@code null} for unlimited depth
     * @return a page of the consent graph with nodes, edges, and pagination info
     */
    ConsentGraph getConsentGraph(
        String cursor,
        int limit,
        String rootType,
        String rootName,
        Integer depth
    );
}
