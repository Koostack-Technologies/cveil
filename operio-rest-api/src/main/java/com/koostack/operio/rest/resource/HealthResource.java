package com.koostack.operio.rest.resource;

import com.koostack.operio.core.TenantService;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.CommonDtos.HealthResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS resource for application-level health checks.
 *
 * This endpoint is public (no authentication required) and is designed
 * for use by load balancers, Kubernetes probes, and monitoring systems.
 *
 * The health check delegates to Operio.verifyReady() which calls
 * isReady() on every configured plugin. If all plugins report ready,
 * the response is 200 with status "UP". If any plugin fails, the
 * response is 503 with status "DOWN" and a list of error descriptions.
 */
@Path("/api/v1/health")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_HEALTH)
public class HealthResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        HealthResource.class
    );

    @Inject
    TenantService tenantService;

    @GET
    @Operation(
        operationId = "healthCheck",
        summary = "Application health check",
        description = "Returns the aggregate health status of all configured Operio "
            + "plugins (consent decision, admin, sync, identity, audit, etc.). "
            + "No authentication required. Suitable for Kubernetes liveness/readiness "
            + "probes and load-balancer health checks."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "All plugins are healthy",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = HealthResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "503",
            description = "One or more plugins are unhealthy",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = HealthResponse.class)
            )
        ),
    })
    public Response health() {
        try {
            tenantService.verifyReady();
            LOG.trace("Health check: UP");
            return Response.ok(HealthResponse.up()).build();
        } catch (OperioConfigurationException e) {
            LOG.warn("Health check: DOWN - {}", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(HealthResponse.down(List.of(e.getMessage())))
                .build();
        } catch (Exception e) {
            LOG.error("Health check: unexpected error", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(HealthResponse.down(List.of(
                    "Unexpected error: " + e.getMessage()
                )))
                .build();
        }
    }
}
