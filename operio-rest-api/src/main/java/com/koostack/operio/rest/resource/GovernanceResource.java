package com.koostack.operio.rest.resource;

import com.koostack.operio.core.TenantService;
import com.koostack.operio.core.models.JurisdictionView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.AdminDtos.AddJurisdictionRequest;
import com.koostack.operio.rest.dto.AdminDtos.AddPurposePolicyRequest;
import com.koostack.operio.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterJurisdictionRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.RemovePurposePolicyRequest;
import com.koostack.operio.rest.dto.AdminDtos.UpdateJurisdictionRequest;
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
 * JAX-RS resource for governance framework administration: managing purposes, regulations,
 * jurisdictions, and their relationships.
 */
@Path("/api/v1/governance")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_GOVERNANCE)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class GovernanceResource {

    private static final Logger LOG = LoggerFactory.getLogger(GovernanceResource.class);

    @Inject TenantService tenantService;

    // -------------------------------------------------------------------
    // Purposes
    // -------------------------------------------------------------------

    @GET
    @Path("/purposes")
    @Operation(
            operationId = "listPurposes",
            summary = "List all purposes with policies and regulation links")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of purposes",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = PurposeView.class))),
    })
    public Response listPurposes(@Context SecurityContext sc) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        List<PurposeView> purposes = tenantService.listPurposes();
        return Response.ok(purposes).build();
    }

    @GET
    @Path("/purposes/{purposeName}")
    @Operation(operationId = "getPurpose", summary = "Get a single purpose aggregate")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Purpose found",
                content = @Content(schema = @Schema(implementation = PurposeView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Purpose not found",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getPurpose(
            @Context SecurityContext sc, @PathParam("purposeName") String purposeName) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        PurposeView purpose = tenantService.getPurpose(purposeName);
        if (purpose == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Purpose not found: " + purposeName))
                    .build();
        }
        return Response.ok(purpose).build();
    }

    @POST
    @Path("/purposes")
    @Operation(operationId = "registerPurpose", summary = "Register a consent purpose (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Purpose registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerPurpose(
            @Context SecurityContext sc, @Valid RegisterPurposeRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.registerPurpose(req.purposeName(), req.description());
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Purpose registered: " + req.purposeName()))
                .build();
    }

    @DELETE
    @Path("/purposes/{purposeName}")
    @Operation(operationId = "removePurpose", summary = "Remove a purpose")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Purpose removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removePurpose(
            @Context SecurityContext sc, @PathParam("purposeName") String purposeName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removePurpose(purposeName);
        return Response.ok(MessageResponse.of("Purpose removed")).build();
    }

    @POST
    @Path("/purposes/policies")
    @Operation(operationId = "addPurposePolicy", summary = "Add a policy to a purpose")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Policy added",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response addPurposePolicy(
            @Context SecurityContext sc, @Valid AddPurposePolicyRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.addPurposePolicy(
                req.purposeName(),
                req.policyType(),
                req.fieldName(),
                req.entityName(),
                req.categoryName());
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Policy added to purpose: " + req.purposeName()))
                .build();
    }

    @DELETE
    @Path("/purposes/policies")
    @Operation(operationId = "removePurposePolicy", summary = "Remove a policy from a purpose")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Policy removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removePurposePolicy(
            @Context SecurityContext sc, @Valid RemovePurposePolicyRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removePurposePolicy(
                req.purposeName(),
                req.policyType(),
                req.fieldName(),
                req.entityName(),
                req.categoryName());
        return Response.ok(MessageResponse.of("Policy removed")).build();
    }

    @POST
    @Path("/purposes/regulations")
    @Operation(operationId = "linkPurposeToRegulation", summary = "Link a purpose to a regulation")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Purpose linked to regulation",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response linkPurposeToRegulation(
            @Context SecurityContext sc, @Valid LinkPurposeRegulationRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.linkPurposeToRegulation(
                req.purposeName(),
                req.regulationName(),
                req.lawfulBasis(),
                req.onGranted(),
                req.onDenied());
        return Response.ok(
                        MessageResponse.of(
                                "Purpose '"
                                        + req.purposeName()
                                        + "' linked to regulation '"
                                        + req.regulationName()
                                        + "'"))
                .build();
    }

    @DELETE
    @Path("/purposes/{purposeName}/regulations/{regulationName}")
    @Operation(
            operationId = "unlinkPurposeFromRegulation",
            summary = "Unlink a purpose from a regulation")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Purpose unlinked from regulation",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response unlinkPurposeFromRegulation(
            @Context SecurityContext sc,
            @PathParam("purposeName") String purposeName,
            @PathParam("regulationName") String regulationName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.unlinkPurposeFromRegulation(purposeName, regulationName);
        return Response.ok(MessageResponse.of("Purpose unlinked from regulation")).build();
    }

    // -------------------------------------------------------------------
    // Regulations
    // -------------------------------------------------------------------

    @GET
    @Path("/regulations")
    @Operation(operationId = "listRegulations", summary = "List all regulations with jurisdictions")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of regulations",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = RegulationView.class))),
    })
    public Response listRegulations(@Context SecurityContext sc) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        List<RegulationView> regulations = tenantService.listRegulations();
        return Response.ok(regulations).build();
    }

    @GET
    @Path("/regulations/{regulationName}")
    @Operation(operationId = "getRegulation", summary = "Get a single regulation aggregate")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Regulation found",
                content = @Content(schema = @Schema(implementation = RegulationView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Regulation not found",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getRegulation(
            @Context SecurityContext sc, @PathParam("regulationName") String regulationName) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        RegulationView reg = tenantService.getRegulation(regulationName);
        if (reg == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(
                            ErrorResponse.of(
                                    "NOT_FOUND", "Regulation not found: " + regulationName))
                    .build();
        }
        return Response.ok(reg).build();
    }

    @POST
    @Path("/regulations")
    @Operation(operationId = "registerRegulation", summary = "Register a regulation (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Regulation registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerRegulation(
            @Context SecurityContext sc, @Valid RegisterRegulationRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.registerRegulation(req.regulationName(), req.description());
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Regulation registered: " + req.regulationName()))
                .build();
    }

    @DELETE
    @Path("/regulations/{regulationName}")
    @Operation(operationId = "removeRegulation", summary = "Remove a regulation")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Regulation removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeRegulation(
            @Context SecurityContext sc, @PathParam("regulationName") String regulationName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removeRegulation(regulationName);
        return Response.ok(MessageResponse.of("Regulation removed")).build();
    }

    @POST
    @Path("/regulations/jurisdictions")
    @Operation(operationId = "addJurisdiction", summary = "Add a jurisdiction to a regulation")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Jurisdiction added",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response addJurisdiction(
            @Context SecurityContext sc, @Valid AddJurisdictionRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.addRegulationJurisdiction(
                req.regulationName(), req.jurisdictionName(), req.description());
        return Response.status(Response.Status.CREATED)
                .entity(
                        MessageResponse.of(
                                "Jurisdiction added to regulation: " + req.regulationName()))
                .build();
    }

    @DELETE
    @Path("/regulations/{regulationName}/jurisdictions/{jurisdictionName}")
    @Operation(
            operationId = "removeRegulationJurisdiction",
            summary = "Remove a jurisdiction from a regulation")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Jurisdiction removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing role",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeRegulationJurisdiction(
            @Context SecurityContext sc,
            @PathParam("regulationName") String regulationName,
            @PathParam("jurisdictionName") String jurisdictionName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removeRegulationJurisdiction(regulationName, jurisdictionName);
        return Response.ok(MessageResponse.of("Jurisdiction removed from regulation")).build();
    }

    // -------------------------------------------------------------------
    // Jurisdictions (standalone CRUD)
    // -------------------------------------------------------------------

    @GET
    @Path("/jurisdictions")
    @Operation(operationId = "listJurisdictions", summary = "List all jurisdictions")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of jurisdictions",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = JurisdictionView.class))),
    })
    public Response listJurisdictions(@Context SecurityContext sc) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        List<JurisdictionView> jurisdictions = tenantService.listJurisdictions();
        return Response.ok(jurisdictions).build();
    }

    @GET
    @Path("/jurisdictions/{jurisdictionName}")
    @Operation(operationId = "getJurisdiction", summary = "Get a single jurisdiction")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Jurisdiction found",
                content = @Content(schema = @Schema(implementation = JurisdictionView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Jurisdiction not found",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getJurisdiction(
            @Context SecurityContext sc, @PathParam("jurisdictionName") String jurisdictionName) {
        Roles.requireRole(sc, Roles.ADMIN_READ);
        return tenantService
                .getJurisdiction(jurisdictionName)
                .map(j -> Response.ok(j).build())
                .orElse(
                        Response.status(Response.Status.NOT_FOUND)
                                .entity(
                                        ErrorResponse.of(
                                                "NOT_FOUND",
                                                "Jurisdiction not found: " + jurisdictionName))
                                .build());
    }

    @POST
    @Path("/jurisdictions")
    @Operation(operationId = "registerJurisdiction", summary = "Register a jurisdiction (upsert)")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Jurisdiction registered",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerJurisdiction(
            @Context SecurityContext sc, @Valid RegisterJurisdictionRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.registerJurisdiction(req.jurisdictionName(), req.description());
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Jurisdiction registered: " + req.jurisdictionName()))
                .build();
    }

    @PUT
    @Path("/jurisdictions/{jurisdictionName}")
    @Operation(operationId = "updateJurisdiction", summary = "Update a jurisdiction description")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Jurisdiction updated",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response updateJurisdiction(
            @Context SecurityContext sc,
            @PathParam("jurisdictionName") String jurisdictionName,
            @Valid UpdateJurisdictionRequest req) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.updateJurisdiction(jurisdictionName, req.description());
        return Response.ok(MessageResponse.of("Jurisdiction updated: " + jurisdictionName)).build();
    }

    @DELETE
    @Path("/jurisdictions/{jurisdictionName}")
    @Operation(
            operationId = "removeJurisdiction",
            summary = "Remove a jurisdiction (fails if still referenced)")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Jurisdiction removed",
                content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "Jurisdiction still referenced",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeJurisdiction(
            @Context SecurityContext sc, @PathParam("jurisdictionName") String jurisdictionName) {
        Roles.requireRole(sc, Roles.ADMIN_WRITE);
        tenantService.removeJurisdiction(jurisdictionName);
        return Response.ok(MessageResponse.of("Jurisdiction removed")).build();
    }
}
