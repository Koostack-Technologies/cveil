package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.models.JurisdictionView;
import com.koostack.cveil.core.models.PurposeView;
import com.koostack.cveil.core.models.RegulationView;
import com.koostack.cveil.plugin.consent.jooq.AddPurposePolicyCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.AddRegulationJurisdictionCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.GetJurisdictionQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetPurposeQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetRegulationQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.LinkPurposeToRegulationCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.ListJurisdictionsQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.ListPurposesQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.ListRegulationsQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterJurisdictionCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterPurposeCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterRegulationCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveJurisdictionCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemovePurposeCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemovePurposePolicyCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveRegulationCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveRegulationJurisdictionCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UnlinkPurposeFromRegulationCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UpdateJurisdictionCommandActuator;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.AdminDtos.AddJurisdictionRequest;
import com.koostack.cveil.rest.dto.AdminDtos.AddPurposePolicyRequest;
import com.koostack.cveil.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterJurisdictionRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RemovePurposePolicyRequest;
import com.koostack.cveil.rest.dto.AdminDtos.UpdateJurisdictionRequest;
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

/**
 * JAX-RS resource for governance framework administration: managing purposes, regulations,
 * jurisdictions, and their relationships.
 */
@Path("/api/v1/governance")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_GOVERNANCE)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class GovernanceResource {

    private static final Logger LOG = LoggerFactory.getLogger(GovernanceResource.class);

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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        List<PurposeView> purposes =
                consentQueryDispatcher.dispatch(new ListPurposesQueryActuator.ListPurposesQuery());
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        PurposeView purpose =
                consentQueryDispatcher.dispatch(
                        new GetPurposeQueryActuator.GetPurposeQuery(purposeName));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RegisterPurposeCommandActuator.RegisterPurposeCommand(
                        req.purposeName(), req.description()));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RemovePurposeCommandActuator.RemovePurposeCommand(purposeName));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "delete"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new AddPurposePolicyCommandActuator.AddPurposePolicyCommand(
                        req.purposeName(),
                        req.policyType(),
                        req.fieldName(),
                        req.entityName(),
                        req.categoryName()));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RemovePurposePolicyCommandActuator.RemovePurposePolicyCommand(
                        req.purposeName(),
                        req.policyType(),
                        req.fieldName(),
                        req.entityName(),
                        req.categoryName()));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "delete"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new LinkPurposeToRegulationCommandActuator.LinkPurposeToRegulationCommand(
                        req.purposeName(),
                        req.regulationName(),
                        req.lawfulBasis(),
                        req.onGranted(),
                        req.onDenied()));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new UnlinkPurposeFromRegulationCommandActuator.UnlinkPurposeFromRegulationCommand(
                        purposeName, regulationName));
        metrics.counter("governance_policies", currentTenantId(), Map.of("operation", "delete"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        List<RegulationView> regulations =
                consentQueryDispatcher.dispatch(
                        new ListRegulationsQueryActuator.ListRegulationsQuery());
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        RegulationView reg =
                consentQueryDispatcher.dispatch(
                        new GetRegulationQueryActuator.GetRegulationQuery(regulationName));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RegisterRegulationCommandActuator.RegisterRegulationCommand(
                        req.regulationName(), req.description()));
        metrics.counter("governance_regulations", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RemoveRegulationCommandActuator.RemoveRegulationCommand(regulationName));
        metrics.counter("governance_regulations", currentTenantId(), Map.of("operation", "delete"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new AddRegulationJurisdictionCommandActuator.AddRegulationJurisdictionCommand(
                        req.regulationName(), req.jurisdictionName(), req.description()));
        metrics.counter("governance_regulations", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RemoveRegulationJurisdictionCommandActuator.RemoveRegulationJurisdictionCommand(
                        regulationName, jurisdictionName));
        metrics.counter("governance_regulations", currentTenantId(), Map.of("operation", "delete"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        List<JurisdictionView> jurisdictions =
                consentQueryDispatcher.dispatch(
                        new ListJurisdictionsQueryActuator.ListJurisdictionsQuery());
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_READ);
        return ((Optional<JurisdictionView>)
                        consentQueryDispatcher.dispatch(
                                new GetJurisdictionQueryActuator.GetJurisdictionQuery(
                                        jurisdictionName)))
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new RegisterJurisdictionCommandActuator.RegisterJurisdictionCommand(
                        req.jurisdictionName(), req.description()));
        metrics.counter(
                "governance_jurisdictions", currentTenantId(), Map.of("operation", "create"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        consentDispatcher.dispatch(
                new UpdateJurisdictionCommandActuator.UpdateJurisdictionCommand(
                        jurisdictionName, req.description()));
        metrics.counter(
                "governance_jurisdictions", currentTenantId(), Map.of("operation", "update"));
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
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        // Invariant: reject removal if regulations still reference this jurisdiction
        // TODO(consent-bizlogic-migrate): move this check into RemoveJurisdictionCommandActuator
        List<RegulationView> allRegulations =
                consentQueryDispatcher.dispatch(
                        new ListRegulationsQueryActuator.ListRegulationsQuery());
        boolean hasAssignments =
                allRegulations.stream()
                        .anyMatch(
                                r ->
                                        r.activeJurisdictions().stream()
                                                .anyMatch(
                                                        j ->
                                                                jurisdictionName.equals(
                                                                        j.jurisdictionName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                    "Cannot remove jurisdiction '"
                            + jurisdictionName
                            + "' — it is still assigned to regulations. Remove it first.");
        }
        consentDispatcher.dispatch(
                new RemoveJurisdictionCommandActuator.RemoveJurisdictionCommand(jurisdictionName));
        metrics.counter(
                "governance_jurisdictions", currentTenantId(), Map.of("operation", "delete"));
        return Response.ok(MessageResponse.of("Jurisdiction removed")).build();
    }
}
