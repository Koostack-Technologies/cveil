package com.koostack.operio.rest.docs;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Serves the tenant-facing (non-platform) API documentation.
 *
 * <ul>
 *   <li>{@code GET /api/v1/docs} — Swagger UI page</li>
 *   <li>{@code GET /api/v1/docs/openapi.json} — filtered OpenAPI spec (JSON)</li>
 * </ul>
 *
 * <p>Both endpoints are public and do not require authentication.
 */
@Path("/api/v1/docs")
public class TenantDocsResource {

    @Inject
    OpenApiSplitService splitService;

    @Inject
    @Location("swagger-ui")
    Template swaggerUi;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    public TemplateInstance swaggerUI() {
        return swaggerUi
            .data("title", "Operio API Documentation")
            .data("specUrl", "/api/v1/docs/openapi.json")
            .data("requiresAuth", false);
    }

    @GET
    @Path("openapi.json")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(hidden = true)
    public String openApiSpec() {
        return splitService.getTenantSpec();
    }
}
