package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.TenantService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS resource for data catalogue administration: managing fields,
 * entities, and categories.
 */
@Path("/api/v1/catalogue")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_CATALOGUE)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class CatalogueResource {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueResource.class);

    @Inject
    TenantService tenantService;

    // -------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------

    @GET
    @Path("/fields")
    @Operation(operationId = "listFields", summary = "List all registered fields with entity and category info")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of fields",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = FieldView.class))),
        @APIResponse(responseCode = "403", description = "Missing role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response listFields(@Context SecurityContext sc) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        List<FieldView> fields = tenantService.listFields();
        return Response.ok(fields).build();
    }

    @POST
    @Path("/fields")
    @Operation(operationId = "registerField", summary = "Register a sensitive field (upsert)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Field registered",
            content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerField(@Context SecurityContext sc, @Valid RegisterFieldRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.registerField(req.fieldName(), req.entityName(),
            req.description(), req.entityDescription());
        return Response.status(Response.Status.CREATED)
            .entity(MessageResponse.of("Field registered: " + req.fieldName()))
            .build();
    }

    @DELETE
    @Path("/fields/{entityName}/{fieldName}")
    @Operation(operationId = "removeField", summary = "Remove a field")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Field removed",
            content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(responseCode = "403", description = "Missing role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeField(@Context SecurityContext sc,
                                @PathParam("entityName") String entityName,
                                @PathParam("fieldName") String fieldName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removeField(fieldName, entityName);
        return Response.ok(MessageResponse.of("Field removed")).build();
    }

    @POST
    @Path("/fields/categories")
    @Operation(operationId = "assignFieldCategory", summary = "Assign a category to a field")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Category assigned",
            content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response assignFieldCategory(@Context SecurityContext sc,
                                        @Valid AssignFieldCategoryRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.assignFieldCategory(req.fieldName(), req.entityName(),
            req.categoryName(), req.categoryDescription());
        return Response.ok(MessageResponse.of(
            "Category '" + req.categoryName() + "' assigned to field '" + req.fieldName() + "'"
        )).build();
    }

    @DELETE
    @Path("/fields/{entityName}/{fieldName}/categories/{categoryName}")
    @Operation(operationId = "unassignFieldCategory", summary = "Remove a category from a field")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Category unassigned",
            content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(responseCode = "403", description = "Missing role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response unassignFieldCategory(@Context SecurityContext sc,
                                          @PathParam("entityName") String entityName,
                                          @PathParam("fieldName") String fieldName,
                                          @PathParam("categoryName") String categoryName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.unassignFieldCategory(fieldName, entityName, categoryName);
        return Response.ok(MessageResponse.of("Category unassigned")).build();
    }
}
