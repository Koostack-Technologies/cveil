package com.koostack.cveil.core.models;

import java.util.List;
import java.util.Map;

/**
 * Domain models for the consent graph read model.
 *
 * <p>The consent graph represents all consent-related entities (fields, purposes, regulations,
 * subjects, categories, entities, jurisdictions, and policies) as nodes with typed, bidirectional
 * edges. Edge property hashes carry metadata such as consent actions and lawful bases.
 */
public final class ConsentGraphModels {

    private ConsentGraphModels() {}

    /**
     * A node in the consent graph.
     *
     * @param type the node type (e.g. {@code "field"}, {@code "purpose"}, {@code "subject"}, {@code
     *     "policy"})
     * @param name the unique name within the type (e.g. {@code "User/email"}, {@code "Marketing"},
     *     {@code "CAT:Personal:Marketing"})
     * @param properties key-value properties stored on the node
     */
    public record GraphNode(String type, String name, Map<String, String> properties) {}

    /**
     * A directed edge in the consent graph, optionally carrying properties.
     *
     * @param sourceType the type of the source node
     * @param sourceName the name of the source node
     * @param targetType the type of the target node
     * @param targetName the name of the target node
     * @param properties key-value properties on the edge (e.g. {@code action}, {@code
     *     lawfulBasis}), or empty if the edge carries no metadata
     */
    public record GraphEdge(
            String sourceType,
            String sourceName,
            String targetType,
            String targetName,
            Map<String, String> properties) {}

    /**
     * A page of the consent graph containing nodes and their connecting edges.
     *
     * @param nodes the nodes in this page
     * @param edges edges connecting the returned nodes (both directions included)
     * @param pageInfo pagination metadata
     */
    public record ConsentGraph(
            List<GraphNode> nodes, List<GraphEdge> edges, GraphPageInfo pageInfo) {}

    /**
     * Cursor-based pagination metadata for graph queries.
     *
     * @param nextCursor opaque cursor for the next page, or {@code null} if this is the last page
     * @param hasMore {@code true} if more pages are available
     * @param totalNodes total node count across all pages (may be approximate for large graphs)
     * @param totalEdges total edge count across all pages (may be approximate for large graphs)
     */
    public record GraphPageInfo(
            String nextCursor, boolean hasMore, int totalNodes, int totalEdges) {}
}
