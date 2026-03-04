package com.koostack.operio.rest.docs;

import com.koostack.operio.rest.auth.Roles;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Serves the platform administration API documentation.
 *
 * <ul>
 *   <li>{@code GET /api/v1/platform/docs} — Swagger UI page with auth gate
 *       (public HTML, but the spec requires authentication)</li>
 *   <li>{@code GET /api/v1/platform/docs/openapi.json} — filtered OpenAPI
 *       spec (JSON, requires super-admin or bootstrap key)</li>
 * </ul>
 *
 * <p>The HTML page itself is public so the browser can render the auth gate
 * form. The OpenAPI spec endpoint requires a valid super-admin API key or
 * bootstrap key (enforced by {@code ApiKeyAuthFilter}).
 */
@Path("/api/v1/platform/docs")
public class PlatformDocsResource {

    @Inject
    OpenApiSplitService splitService;

    @Inject
    @Named("swagger-ui")
    Template swaggerUi;

    /**
     * Serves the Swagger UI page with an auth gate. The page is public —
     * the auth requirement is enforced when the browser fetches the spec.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    public TemplateInstance swaggerUI() {
        return swaggerUi
            .data("title", "Operio Platform Administration API")
            .data("specUrl", "/api/v1/platform/docs/openapi.json")
            .data("requiresAuth", true);
    }

    /**
     * Returns the platform-only OpenAPI spec. This endpoint goes through
     * normal auth (not in the public path list), so the caller must
     * provide a valid super-admin API key or bootstrap key.
     */
    @GET
    @Path("openapi.json")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(hidden = true)
    public String openApiSpec(@Context SecurityContext sec) {
        Roles.requireRole(sec, Roles.SUPER_ADMIN);
        return splitService.getPlatformSpec();
    }
}
