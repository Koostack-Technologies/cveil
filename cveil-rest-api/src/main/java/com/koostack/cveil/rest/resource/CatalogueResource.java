package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.models.CategoryView;
import com.koostack.cveil.core.models.EntityView;
import com.koostack.cveil.core.models.FieldView;
import com.koostack.cveil.plugin.consent.jooq.AssignFieldCategoryCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.GetCategoryQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetEntityQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.ListCategoriesQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.ListEntitiesQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.ListFieldsQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterCategoryCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterEntityCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterFieldCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveCategoryCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveEntityCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveFieldCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UnassignFieldCategoryCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UpdateCategoryCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UpdateEntityCommandActuator;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterCategoryRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterEntityRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.cveil.rest.dto.AdminDtos.UpdateCategoryRequest;
import com.koostack.cveil.rest.dto.AdminDtos.UpdateEntityRequest;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import com.koostack.cveil.rest.util.MetricsHelper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/** JAX-RS resource for data catalogue administration: managing fields, entities, and categories. */
@Path("/api/v1/catalogue")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CATALOGUE)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class CatalogueResource {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueResource.class);

    @Inject
    @Named("consentDispatcher")
    CommandDispatcher consentDispatcher;

    @Inject
    @Named("consentQueryDispatcher")
    QueryDispatcher consentQueryDispatcher;

    @Inject CveilRequestHolder requestHolder;

    @Inject MetricsHelper metrics;

    private String currentTenantId() {
        return requestHolder.identity() != null ? requestHolder.identity().tenantId() : null;
    }

    // -------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------

    @GET
    @Path("/fields")
    @Operation(
            operationId = "listFields",
            summary = "List all registered fields with entity and category info")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of fields",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = FieldView.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response listFields(@Context SecurityContext sc) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_READ);
        List<FieldView> fields =
                consentQueryDispatcher.dispatch(new ListFieldsQueryActuator.ListFieldsQuery());
        return Response.ok(fields).build();
    }

    @POST
    @Path("/fields")
    @Operation(operationId = "registerField", summary = "Register a sensitive field (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Field registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerField(@Context SecurityContext sc, @Valid RegisterFieldRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new RegisterFieldCommandActuator.RegisterFieldCommand(
                        req.fieldName(),
                        req.entityName(),
                        req.description(),
                        req.entityDescription()));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "create_field", "entityType", "field"));
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Field registered: " + req.fieldName()))
                .build();
    }

    @DELETE
    @Path("/fields/{entityName}/{fieldName}")
    @Operation(operationId = "removeField", summary = "Remove a field")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Field removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeField(
            @Context SecurityContext sc,
            @PathParam("entityName") String entityName,
            @PathParam("fieldName") String fieldName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new RemoveFieldCommandActuator.RemoveFieldCommand(fieldName, entityName));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "delete_field", "entityType", "field"));
        return Response.ok(MessageResponse.of("Field removed")).build();
    }

    @POST
    @Path("/fields/categories")
    @Operation(operationId = "assignFieldCategory", summary = "Assign a category to a field")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Category assigned",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response assignFieldCategory(
            @Context SecurityContext sc, @Valid AssignFieldCategoryRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        var command =
                new AssignFieldCategoryCommandActuator.AssignFieldCategoryCommand(
                        req.fieldName(),
                        req.entityName(),
                        req.categoryName(),
                        req.categoryDescription());
        consentDispatcher.dispatch(command);
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "add_field_category", "entityType", "field_category"));
        return Response.ok(
                        MessageResponse.of(
                                "Category '"
                                        + req.categoryName()
                                        + "' assigned to field '"
                                        + req.fieldName()
                                        + "'"))
                .build();
    }

    @DELETE
    @Path("/fields/{entityName}/{fieldName}/categories/{categoryName}")
    @Operation(operationId = "unassignFieldCategory", summary = "Remove a category from a field")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Category unassigned",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response unassignFieldCategory(
            @Context SecurityContext sc,
            @PathParam("entityName") String entityName,
            @PathParam("fieldName") String fieldName,
            @PathParam("categoryName") String categoryName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new UnassignFieldCategoryCommandActuator.UnassignFieldCategoryCommand(
                        fieldName, entityName, categoryName));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "remove_field_category", "entityType", "field_category"));
        return Response.ok(MessageResponse.of("Category unassigned")).build();
    }

    // -------------------------------------------------------------------
    // Entities (standalone CRUD)
    // -------------------------------------------------------------------

    @GET
    @Path("/entities")
    @Operation(operationId = "listEntities", summary = "List all entities")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of entities",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = EntityView.class))),
    })
    public Response listEntities(@Context SecurityContext sc) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_READ);
        List<EntityView> entities =
                consentQueryDispatcher.dispatch(new ListEntitiesQueryActuator.ListEntitiesQuery());
        return Response.ok(entities).build();
    }

    @GET
    @Path("/entities/{entityName}")
    @Operation(operationId = "getEntity", summary = "Get a single entity")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Entity found",
                content = @Content(schema = @Schema(implementation = EntityView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Entity not found",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getEntity(
            @Context SecurityContext sc, @PathParam("entityName") String entityName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_READ);
        return ((Optional<EntityView>)
                        consentQueryDispatcher.dispatch(
                                new GetEntityQueryActuator.GetEntityQuery(entityName)))
                .map(e -> Response.ok(e).build())
                .orElse(
                        Response.status(Response.Status.NOT_FOUND)
                                .entity(
                                        ErrorResponse.of(
                                                "NOT_FOUND", "Entity not found: " + entityName))
                                .build());
    }

    @POST
    @Path("/entities")
    @Operation(operationId = "registerEntity", summary = "Register an entity (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Entity registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerEntity(@Context SecurityContext sc, @Valid RegisterEntityRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new RegisterEntityCommandActuator.RegisterEntityCommand(
                        req.entityName(), req.description()));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "create_entity", "entityType", "entity"));
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Entity registered: " + req.entityName()))
                .build();
    }

    @PUT
    @Path("/entities/{entityName}")
    @Operation(operationId = "updateEntity", summary = "Update an entity's description")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Entity updated",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response updateEntity(
            @Context SecurityContext sc,
            @PathParam("entityName") String entityName,
            @Valid UpdateEntityRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new UpdateEntityCommandActuator.UpdateEntityCommand(entityName, req.description()));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "update_entity", "entityType", "entity"));
        return Response.ok(MessageResponse.of("Entity updated: " + entityName)).build();
    }

    @DELETE
    @Path("/entities/{entityName}")
    @Operation(
            operationId = "removeEntity",
            summary = "Remove an entity (fails if fields still reference it)")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Entity removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "Entity still referenced by fields",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeEntity(
            @Context SecurityContext sc, @PathParam("entityName") String entityName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        // Invariant: reject removal if fields still reference this entity
        // TODO(consent-bizlogic-migrate): move this check into RemoveEntityCommandActuator
        List<FieldView> fields =
                consentQueryDispatcher.dispatch(new ListFieldsQueryActuator.ListFieldsQuery());
        boolean hasFields =
                fields.stream()
                        .anyMatch(
                                f ->
                                        f.entity() != null
                                                && entityName.equals(f.entity().entityName()));
        if (hasFields) {
            throw new IllegalStateException(
                    "Cannot remove entity '"
                            + entityName
                            + "' — it still has fields assigned. Remove them first.");
        }
        consentDispatcher.dispatch(new RemoveEntityCommandActuator.RemoveEntityCommand(entityName));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "delete_entity", "entityType", "entity"));
        return Response.ok(MessageResponse.of("Entity removed")).build();
    }

    // -------------------------------------------------------------------
    // Categories (standalone CRUD)
    // -------------------------------------------------------------------

    @GET
    @Path("/categories")
    @Operation(operationId = "listCategories", summary = "List all categories")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of categories",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = CategoryView.class))),
    })
    public Response listCategories(@Context SecurityContext sc) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_READ);
        List<CategoryView> categories =
                consentQueryDispatcher.dispatch(
                        new ListCategoriesQueryActuator.ListCategoriesQuery());
        return Response.ok(categories).build();
    }

    @GET
    @Path("/categories/{categoryName}")
    @Operation(operationId = "getCategory", summary = "Get a single category")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Category found",
                content = @Content(schema = @Schema(implementation = CategoryView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Category not found",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getCategory(
            @Context SecurityContext sc, @PathParam("categoryName") String categoryName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_READ);
        return ((Optional<CategoryView>)
                        consentQueryDispatcher.dispatch(
                                new GetCategoryQueryActuator.GetCategoryQuery(categoryName)))
                .map(c -> Response.ok(c).build())
                .orElse(
                        Response.status(Response.Status.NOT_FOUND)
                                .entity(
                                        ErrorResponse.of(
                                                "NOT_FOUND", "Category not found: " + categoryName))
                                .build());
    }

    @POST
    @Path("/categories")
    @Operation(operationId = "registerCategory", summary = "Register a category (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Category registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerCategory(
            @Context SecurityContext sc, @Valid RegisterCategoryRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new RegisterCategoryCommandActuator.RegisterCategoryCommand(
                        req.categoryName(), req.description()));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "create_category", "entityType", "category"));
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Category registered: " + req.categoryName()))
                .build();
    }

    @PUT
    @Path("/categories/{categoryName}")
    @Operation(operationId = "updateCategory", summary = "Update a category's description")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Category updated",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response updateCategory(
            @Context SecurityContext sc,
            @PathParam("categoryName") String categoryName,
            @Valid UpdateCategoryRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        consentDispatcher.dispatch(
                new UpdateCategoryCommandActuator.UpdateCategoryCommand(
                        categoryName, req.description()));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "update_category", "entityType", "category"));
        return Response.ok(MessageResponse.of("Category updated: " + categoryName)).build();
    }

    @DELETE
    @Path("/categories/{categoryName}")
    @Operation(
            operationId = "removeCategory",
            summary = "Remove a category (fails if still referenced)")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Category removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "Category still referenced by fields",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeCategory(
            @Context SecurityContext sc, @PathParam("categoryName") String categoryName) {
        Permissions.require(requestHolder.authorization(), Permissions.CATALOGUE_WRITE);
        // Invariant: reject removal if fields still reference this category
        // TODO(consent-bizlogic-migrate): move this check into RemoveCategoryCommandActuator
        List<FieldView> allFields =
                consentQueryDispatcher.dispatch(new ListFieldsQueryActuator.ListFieldsQuery());
        boolean hasAssignments =
                allFields.stream()
                        .anyMatch(
                                f ->
                                        f.categories().stream()
                                                .anyMatch(
                                                        c ->
                                                                categoryName.equals(
                                                                        c.categoryName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                    "Cannot remove category '"
                            + categoryName
                            + "' — it is still assigned to fields. Unassign it first.");
        }
        consentDispatcher.dispatch(
                new RemoveCategoryCommandActuator.RemoveCategoryCommand(categoryName));
        metrics.counter(
                "catalogue_mutations",
                currentTenantId(),
                Map.of("operation", "delete_category", "entityType", "category"));
        return Response.ok(MessageResponse.of("Category removed")).build();
    }
}
