package com.koostack.cveil.rest.resource;

import com.koostack.cveil.platform.webhook.WebhookDeliveryService;
import com.koostack.cveil.platform.webhook.model.WebhookEndpoint;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manage outbound webhook endpoints. Tenants register URLs that receive domain-event notifications
 * (consent grants, revocations, subject deletions, governance changes).
 *
 * <h2>Delivery semantics</h2>
 *
 * <p>Webhook delivery is <strong>at-least-once</strong>. If the Cveil process crashes between
 * delivering a payload and acknowledging the originating stream entry, the event will be
 * redelivered. Consumers <strong>must</strong> deduplicate on the {@code eventId} field present in
 * every payload body.
 */
@Path("/api/v1/webhooks/endpoints")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_ADMIN)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
@Blocking
public class WebhookManagementResource {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookManagementResource.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Valid event types that can be subscribed to. Wildcard '*' subscribes to all. */
    private static final Set<String> VALID_EVENT_TYPES =
            Set.of(
                    "*",
                    "test.ping",
                    "consent.subject.granted",
                    "consent.subject.revoked",
                    "consent.subject.all-revoked",
                    "consent.subject-entity.registered",
                    "consent.subject-entity.removed",
                    "consent.dsar.data-deleted",
                    "consent.dsar.shredded",
                    "consent.regulation.registered",
                    "consent.regulation.updated",
                    "consent.regulation.removed",
                    "consent.purpose.registered",
                    "consent.purpose.updated",
                    "consent.purpose.removed",
                    "consent.jurisdiction.registered",
                    "consent.jurisdiction.updated",
                    "consent.jurisdiction.removed");

    @Inject
    @Named("webhookDeliveryService")
    WebhookDeliveryService webhookDeliveryService;

    @Inject CveilRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @GET
    @Operation(operationId = "listWebhookEndpoints", summary = "List registered webhook endpoints")
    public Response list() {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);
        List<WebhookEndpoint> endpoints =
                webhookDeliveryService.listEndpoints(requestHolder.identity().tenantId());
        return Response.ok(endpoints).build();
    }

    @POST
    @Operation(operationId = "createWebhookEndpoint", summary = "Register a new webhook endpoint")
    public Response create(CreateWebhookRequest body) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        if (body == null || body.url() == null || body.url().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("'url' is required"))
                    .build();
        }
        if (body.events() == null || body.events().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.validation(
                                    "'events' must contain at least one event type"))
                    .build();
        }

        // SSRF protection: validate URL before persisting
        String urlError = WebhookDeliveryService.validateWebhookUrl(body.url());
        if (urlError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation(urlError))
                    .build();
        }

        // Validate event types against whitelist
        for (String event : body.events()) {
            if (!VALID_EVENT_TYPES.contains(event)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(
                                ErrorResponse.validation(
                                        "Unknown event type: '"
                                                + event
                                                + "'. Valid: "
                                                + VALID_EVENT_TYPES))
                        .build();
            }
        }

        String secret = generateSecret();
        WebhookEndpoint created =
                webhookDeliveryService.createEndpoint(
                        requestHolder.identity().tenantId(),
                        body.url(),
                        secret,
                        body.events(),
                        body.description());

        // Return with the plaintext secret — only shown once
        return Response.status(Response.Status.CREATED)
                .entity(
                        Map.of(
                                "id", created.id(),
                                "url", created.url(),
                                "secret", secret,
                                "events", created.events(),
                                "enabled", created.enabled(),
                                "description",
                                        created.description() != null ? created.description() : ""))
                .build();
    }

    @PUT
    @Path("/{endpointId}")
    @Operation(operationId = "updateWebhookEndpoint", summary = "Update a webhook endpoint")
    public Response update(@PathParam("endpointId") UUID endpointId, UpdateWebhookRequest body) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        // SSRF protection on URL update
        if (body.url() != null) {
            String urlError = WebhookDeliveryService.validateWebhookUrl(body.url());
            if (urlError != null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ErrorResponse.validation(urlError))
                        .build();
            }
        }

        boolean updated =
                webhookDeliveryService.updateEndpoint(
                        requestHolder.identity().tenantId(),
                        endpointId,
                        body.url(),
                        body.events(),
                        body.enabled(),
                        body.description());

        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Webhook endpoint not found"))
                    .build();
        }
        return Response.ok(Map.of("updated", true)).build();
    }

    @DELETE
    @Path("/{endpointId}")
    @Operation(operationId = "deleteWebhookEndpoint", summary = "Delete a webhook endpoint")
    public Response delete(@PathParam("endpointId") UUID endpointId) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        boolean deleted =
                webhookDeliveryService.deleteEndpoint(
                        requestHolder.identity().tenantId(), endpointId);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Webhook endpoint not found"))
                    .build();
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/{endpointId}/deliveries")
    @Operation(
            operationId = "getWebhookDeliveries",
            summary = "View delivery history for an endpoint")
    public Response deliveries(
            @PathParam("endpointId") UUID endpointId,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        List<Object> logs = webhookDeliveryService.getDeliveryLog(endpointId, limit);
        return Response.ok(logs).build();
    }

    @POST
    @Path("/{endpointId}/test")
    @Operation(operationId = "testWebhookEndpoint", summary = "Send a test event to an endpoint")
    public Response test(@PathParam("endpointId") UUID endpointId) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        List<WebhookEndpoint> all =
                webhookDeliveryService.listEndpoints(requestHolder.identity().tenantId());
        WebhookEndpoint ep =
                all.stream().filter(e -> e.id().equals(endpointId)).findFirst().orElse(null);
        if (ep == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Webhook endpoint not found"))
                    .build();
        }

        String testPayload =
                "{\"eventType\":\"test.ping\",\"tenantId\":\""
                        + requestHolder.identity().tenantId()
                        + "\",\"data\":{\"message\":\"This is a test webhook delivery from"
                        + " Cveil.\"}}";

        // Reset circuit breaker so test ping always goes through even if the
        // endpoint was auto-disabled.
        webhookDeliveryService.resetCircuitBreaker(endpointId);

        // Re-fetch with secrets unmasked after the reset may have re-enabled.
        List<WebhookEndpoint> unmasked =
                webhookDeliveryService.findMatchingEndpoints(
                        requestHolder.identity().tenantId(), "test.ping");
        WebhookEndpoint real =
                unmasked.stream().filter(e -> e.id().equals(endpointId)).findFirst().orElse(null);

        boolean success =
                real != null
                        ? webhookDeliveryService.deliver(real, "test.ping", testPayload)
                        : false;

        return Response.ok(Map.of("delivered", success)).build();
    }

    @POST
    @Path("/{endpointId}/rotate-secret")
    @Operation(
            operationId = "rotateWebhookSecret",
            summary = "Rotate the signing secret for a webhook endpoint")
    public Response rotateSecret(@PathParam("endpointId") UUID endpointId) {
        Permissions.require(requestHolder.authorization(), Permissions.ORG_WRITE);

        List<WebhookEndpoint> all =
                webhookDeliveryService.listEndpoints(requestHolder.identity().tenantId());
        WebhookEndpoint ep =
                all.stream().filter(e -> e.id().equals(endpointId)).findFirst().orElse(null);
        if (ep == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Webhook endpoint not found"))
                    .build();
        }

        String newSecret = generateSecret();
        boolean rotated =
                webhookDeliveryService.rotateEndpointSecret(
                        endpointId, requestHolder.identity().tenantId(), newSecret);
        if (!rotated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NOT_FOUND", "Webhook endpoint not found"))
                    .build();
        }
        return Response.ok(
                        Map.of(
                                "secret",
                                newSecret,
                                "previousSecretExpiresAt",
                                Instant.now()
                                        .plus(
                                                com.koostack.cveil.platform.webhook
                                                        .WebhookDeliveryService
                                                        .SECRET_ROTATION_GRACE)
                                        .toString()))
                .build();
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    public record CreateWebhookRequest(String url, List<String> events, String description) {}

    public record UpdateWebhookRequest(
            String url, List<String> events, Boolean enabled, String description) {}

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
