package com.koostack.operio.rest.resource;

import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.operio.core.models.ConsentGraphModels.GraphPageInfo;
import com.koostack.operio.plugin.consent.jooq.GetConsentGraphQueryActuator;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * JAX-RS resource exposing the consent graph for visualization and inspection.
 *
 * <p>Provides a paginated view of the full consent topology (nodes and edges) stored in the Redis
 * graph projection. Supports optional BFS subgraph filtering from a given root node.
 */
@Path("/api/v1/consent")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
@RequestScoped
public class GraphResource {

    @Inject
    @Named("consentQueryDispatcher")
    QueryDispatcher consentQueryDispatcher;

    @GET
    @Path("/graph")
    @Operation(
            operationId = "getConsentGraph",
            summary = "Retrieve the consent graph",
            description =
                    "Returns a paginated view of the consent topology graph "
                            + "with nodes and edges. Optionally performs a BFS traversal from "
                            + "a root node to a given depth.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Graph page with nodes, edges, and pagination info",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ConsentGraphResponse.class))),
        @APIResponse(responseCode = "403", description = "Missing required role"),
        @APIResponse(responseCode = "400", description = "Invalid parameters")
    })
    public Response getGraph(
            @Context SecurityContext securityContext,
            @Parameter(description = "Opaque cursor for pagination") @QueryParam("cursor")
                    String cursor,
            @Parameter(description = "Max nodes per page (1-1000)")
                    @QueryParam("limit")
                    @DefaultValue("100")
                    int limit,
            @Parameter(
                            description =
                                    "Root node type for BFS subgraph (e.g. 'purpose',"
                                            + " 'regulation')")
                    @QueryParam("rootType")
                    String rootType,
            @Parameter(description = "Root node name for BFS subgraph") @QueryParam("rootName")
                    String rootName,
            @Parameter(description = "BFS traversal depth limit") @QueryParam("depth")
                    Integer depth) {
        Roles.requireRole(securityContext, Roles.CONSENT_READ);

        int clampedLimit = Math.max(1, Math.min(limit, 1000));

        ConsentGraph graph =
                consentQueryDispatcher.dispatch(
                        new GetConsentGraphQueryActuator.GetConsentGraphQuery(
                                cursor, clampedLimit, rootType, rootName, depth));

        return Response.ok(ConsentGraphResponse.from(graph)).build();
    }

    // -----------------------------------------------------------------------
    // Response DTO
    // -----------------------------------------------------------------------

    @Schema(description = "Paginated consent graph response")
    public record ConsentGraphResponse(
            @Schema(description = "Graph nodes in this page") List<NodeDto> nodes,
            @Schema(description = "Graph edges connecting nodes in this page") List<EdgeDto> edges,
            @Schema(description = "Pagination info") PageInfoDto pageInfo) {
        static ConsentGraphResponse from(ConsentGraph graph) {
            List<NodeDto> nodes =
                    graph.nodes().stream()
                            .map(n -> new NodeDto(n.type(), n.name(), n.properties()))
                            .toList();
            List<EdgeDto> edges =
                    graph.edges().stream()
                            .map(
                                    e ->
                                            new EdgeDto(
                                                    e.sourceType(),
                                                    e.sourceName(),
                                                    e.targetType(),
                                                    e.targetName(),
                                                    e.properties()))
                            .toList();
            GraphPageInfo pi = graph.pageInfo();
            return new ConsentGraphResponse(
                    nodes,
                    edges,
                    new PageInfoDto(
                            pi.nextCursor(), pi.hasMore(), pi.totalNodes(), pi.totalEdges()));
        }
    }

    @Schema(description = "A node in the consent graph")
    public record NodeDto(
            @Schema(description = "Node type (field, purpose, regulation, etc.)") String type,
            @Schema(description = "Node name (unique within type)") String name,
            @Schema(description = "Node properties") Map<String, String> properties) {}

    @Schema(description = "An edge in the consent graph")
    public record EdgeDto(
            String sourceType,
            String sourceName,
            String targetType,
            String targetName,
            @Schema(description = "Edge properties (may be empty)")
                    Map<String, String> properties) {}

    @Schema(description = "Pagination metadata")
    public record PageInfoDto(
            @Schema(description = "Cursor for the next page, null if no more pages")
                    String nextCursor,
            boolean hasMore,
            int totalNodes,
            int totalEdges) {}
}
