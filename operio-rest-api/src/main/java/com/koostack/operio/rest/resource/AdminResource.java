package com.koostack.operio.rest.resource;

import com.koostack.operio.core.Models.CategoryInfo;
import com.koostack.operio.core.Models.EntityInfo;
import com.koostack.operio.core.Models.FieldInfo;
import com.koostack.operio.core.Models.PurposeInfo;
import com.koostack.operio.core.Models.RegulationInfo;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.AdminDtos.CategoryResponse;
import com.koostack.operio.rest.dto.AdminDtos.EntityResponse;
import com.koostack.operio.rest.dto.AdminDtos.FieldResponse;
import com.koostack.operio.rest.dto.AdminDtos.LinkFieldCategoryRequest;
import com.koostack.operio.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.PurposeResponse;
import com.koostack.operio.rest.dto.AdminDtos.RegisterCategoryRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegulationResponse;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
 * JAX-RS resource for consent schema administration: managing fields,
 * categories, entities, purposes, regulations, and their relationships.
 *
 * <p>All endpoints require authentication and operate within a tenant scope.
 *
 * <h2>RBAC Enforcement</h2>
 * <ul>
 *   <li>Read-only endpoints require the admin:read role.</li>
 *   <li>Mutating endpoints require the admin:write role.</li>
 *   <li>The super-admin role bypasses all checks.</li>
 * </ul>
 *
 * @see Roles
 */
@Path("/api/v1/admin")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_ADMIN)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class AdminResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        AdminResource.class
    );

    @Inject
    Operio operio;

    // -------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------

    @GET
    @Path("/fields")
    @Operation(
        operationId = "listFields",
        summary = "List all registered sensitive fields",
        description = "Returns every field registered in the consent schema. "
            + "Requires the admin:read role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of registered fields",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(
                    type = SchemaType.ARRAY,
                    implementation = FieldResponse.class
                )
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:read)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response listFields(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.ADMIN_READ);

        LOG.debug("GET /admin/fields");

        ConsentAdminService<?> admin = operio.consentAdminService();
        List<FieldInfo> fields = admin.listFields();

        List<FieldResponse> dtos = fields
            .stream()
            .map(FieldResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @POST
    @Path("/fields")
    @Operation(
        operationId = "registerField",
        summary = "Register a sensitive field",
        description = "Registers a sensitive field in the consent schema using upsert "
            + "semantics. If a field with the same name and entity already exists, "
            + "its description is updated. Requires the admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Field registered successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response registerField(
        @Context SecurityContext securityContext,
        @Valid RegisterFieldRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug(
            "POST /admin/fields - field={}, entity={}",
            request.fieldName(),
            request.entityName()
        );

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.registerField(
            request.fieldName(),
            request.entityName(),
            request.description()
        );

        return Response.status(Response.Status.CREATED)
            .entity(
                MessageResponse.of("Field registered: " + request.fieldName())
            )
            .build();
    }

    // -------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------

    @GET
    @Path("/categories")
    @Operation(
        operationId = "listCategories",
        summary = "List all registered consent categories",
        description = "Returns every category registered in the consent schema. "
            + "Requires the admin:read role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of registered categories",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(
                    type = SchemaType.ARRAY,
                    implementation = CategoryResponse.class
                )
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:read)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response listCategories(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.ADMIN_READ);

        LOG.debug("GET /admin/categories");

        ConsentAdminService<?> admin = operio.consentAdminService();
        List<CategoryInfo> categories = admin.listCategories();

        List<CategoryResponse> dtos = categories
            .stream()
            .map(CategoryResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @POST
    @Path("/categories")
    @Operation(
        operationId = "registerCategory",
        summary = "Register a consent category",
        description = "Registers a consent category using upsert semantics. "
            + "Requires the admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Category registered successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response registerCategory(
        @Context SecurityContext securityContext,
        @Valid RegisterCategoryRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug(
            "POST /admin/categories - category={}",
            request.categoryName()
        );

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.registerCategory(request.categoryName(), request.description());

        return Response.status(Response.Status.CREATED)
            .entity(
                MessageResponse.of(
                    "Category registered: " + request.categoryName()
                )
            )
            .build();
    }

    // -------------------------------------------------------------------
    // Entities
    // -------------------------------------------------------------------

    @GET
    @Path("/entities")
    @Operation(
        operationId = "listEntities",
        summary = "List all distinct entities",
        description = "Returns all entities that have registered fields. Entities are "
            + "implicitly created when fields with entity names are registered. "
            + "Requires the admin:read role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of entities",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(
                    type = SchemaType.ARRAY,
                    implementation = EntityResponse.class
                )
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:read)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response listEntities(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.ADMIN_READ);

        LOG.debug("GET /admin/entities");

        ConsentAdminService<?> admin = operio.consentAdminService();
        List<EntityInfo> entities = admin.listEntities();

        List<EntityResponse> dtos = entities
            .stream()
            .map(EntityResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    // -------------------------------------------------------------------
    // Purposes
    // -------------------------------------------------------------------

    @GET
    @Path("/purposes")
    @Operation(
        operationId = "listPurposes",
        summary = "List all registered consent purposes",
        description = "Returns every purpose registered in the consent schema. "
            + "Requires the admin:read role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of registered purposes",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(
                    type = SchemaType.ARRAY,
                    implementation = PurposeResponse.class
                )
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:read)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response listPurposes(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.ADMIN_READ);

        LOG.debug("GET /admin/purposes");

        ConsentAdminService<?> admin = operio.consentAdminService();
        List<PurposeInfo> purposes = admin.listPurposes();

        List<PurposeResponse> dtos = purposes
            .stream()
            .map(PurposeResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @POST
    @Path("/purposes")
    @Operation(
        operationId = "registerPurpose",
        summary = "Register a consent purpose",
        description = "Registers a consent purpose using upsert semantics. "
            + "Requires the admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Purpose registered successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response registerPurpose(
        @Context SecurityContext securityContext,
        @Valid RegisterPurposeRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug("POST /admin/purposes - purpose={}", request.purposeName());

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.registerPurpose(request.purposeName(), request.description());

        return Response.status(Response.Status.CREATED)
            .entity(
                MessageResponse.of(
                    "Purpose registered: " + request.purposeName()
                )
            )
            .build();
    }

    // -------------------------------------------------------------------
    // Regulations
    // -------------------------------------------------------------------

    @GET
    @Path("/regulations")
    @Operation(
        operationId = "listRegulations",
        summary = "List all registered regulations",
        description = "Returns every regulation registered in the consent schema. "
            + "Requires the admin:read role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of registered regulations",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(
                    type = SchemaType.ARRAY,
                    implementation = RegulationResponse.class
                )
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:read)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response listRegulations(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.ADMIN_READ);

        LOG.debug("GET /admin/regulations");

        ConsentAdminService<?> admin = operio.consentAdminService();
        List<RegulationInfo> regulations = admin.listRegulations();

        List<RegulationResponse> dtos = regulations
            .stream()
            .map(RegulationResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @POST
    @Path("/regulations")
    @Operation(
        operationId = "registerRegulation",
        summary = "Register a regulation",
        description = "Registers a regulation using upsert semantics. Requires the "
            + "admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Regulation registered successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response registerRegulation(
        @Context SecurityContext securityContext,
        @Valid RegisterRegulationRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug(
            "POST /admin/regulations - regulation={}",
            request.regulationName()
        );

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.registerRegulation(
            request.regulationName(),
            request.fullName(),
            request.jurisdiction(),
            request.description(),
            request.url()
        );

        return Response.status(Response.Status.CREATED)
            .entity(
                MessageResponse.of(
                    "Regulation registered: " + request.regulationName()
                )
            )
            .build();
    }

    // -------------------------------------------------------------------
    // Links -- Field to Category
    // -------------------------------------------------------------------

    @POST
    @Path("/fields/links")
    @Operation(
        operationId = "linkFieldToCategory",
        summary = "Link a field to a category",
        description = "Associates a registered field with a registered category. "
            + "Idempotent: existing links are a no-op. Requires the admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Link created (or already existed)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response linkFieldToCategory(
        @Context SecurityContext securityContext,
        @Valid LinkFieldCategoryRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug(
            "POST /admin/fields/links - field={}, entity={}, category={}",
            request.fieldName(),
            request.entityName(),
            request.categoryName()
        );

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.linkFieldToCategory(
            request.fieldName(),
            request.entityName(),
            request.categoryName()
        );

        return Response.ok(
            MessageResponse.of(
                "Field '" +
                    request.fieldName() +
                    "' linked to category '" +
                    request.categoryName() +
                    "'"
            )
        ).build();
    }

    // -------------------------------------------------------------------
    // Links -- Purpose to Regulation
    // -------------------------------------------------------------------

    @POST
    @Path("/purposes/links")
    @Operation(
        operationId = "linkPurposeToRegulation",
        summary = "Link a purpose to a regulation",
        description = "Associates a registered purpose with a registered regulation "
            + "and its legal basis. Idempotent: existing links are a no-op. "
            + "Requires the admin:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Link created (or already existed)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = MessageResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Validation error",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "403",
            description = "Missing required role (admin:write)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
    })
    public Response linkPurposeToRegulation(
        @Context SecurityContext securityContext,
        @Valid LinkPurposeRegulationRequest request
    ) {
        Roles.requireRole(securityContext, Roles.ADMIN_WRITE);

        LOG.debug(
            "POST /admin/purposes/links - purpose={}, regulation={}, basis={}",
            request.purposeName(),
            request.regulationName(),
            request.legalBasis()
        );

        ConsentAdminService<?> admin = operio.consentAdminService();
        admin.linkPurposeToRegulation(
            request.purposeName(),
            request.regulationName(),
            request.legalBasis()
        );

        return Response.ok(
            MessageResponse.of(
                "Purpose '" +
                    request.purposeName() +
                    "' linked to regulation '" +
                    request.regulationName() +
                    "'"
            )
        ).build();
    }
}
