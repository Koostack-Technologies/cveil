package com.koostack.operio.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSink;
import com.koostack.operio.core.tenant.consent.invalidation.SubscriptionManager;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSubscriber;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.interceptor.TenantScoped;
import com.koostack.operio.core.models.TenantContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * SSE endpoint that streams admin cache invalidation signals to connected
 * clients.
 *
 * <p>When an admin aggregate (Field, Purpose, Regulation, SubjectConsent)
 * is modified, the Redis admin projection publishes an invalidation message
 * to the {@code operio:consent-admin-invalidation:{tenantId}} Pub/Sub
 * channel. This endpoint bridges those signals to clients via Server-Sent
 * Events.
 *
 * <p>Clients can use these signals to refresh their local caches or UI.
 */
@Path("/api/v1/admin")
@TenantScoped
@Tag(ref = OperioOpenApiConfig.TAG_ADMIN)
public class AdminEventsResource {

    private static final Logger log = LoggerFactory.getLogger(AdminEventsResource.class);

    @Inject
    @Named("adminSubscriptionManager")
    SubscriptionManager adminSubscriptionManager;

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(operationId = "adminEvents",
            summary = "Stream admin cache invalidation signals via SSE")
    public Multi<CacheInvalidationMessage> streamAdminEvents(@Context SecurityContext sc) {
        Roles.requireRole(sc, Roles.ADMIN_READ);

        if (adminSubscriptionManager == null) {
            log.warn("SSE admin events requested but subscription manager not available");
            return Multi.createFrom().empty();
        }

        String tenantId = TenantContext.require();
        String connectionId = UUID.randomUUID().toString();

        log.info("SSE client connected for admin events — tenant={}, connection={}",
                tenantId, connectionId);

        return Multi.createFrom().<CacheInvalidationMessage>emitter(emitter -> {
            InvalidationSink sink = message -> {
                if (!emitter.isCancelled()) {
                    emitter.emit(message);
                }
            };

            InvalidationSubscriber.Subscription subscription =
                    adminSubscriptionManager.add(connectionId, tenantId, sink);

            emitter.onTermination(() -> {
                log.info("SSE client disconnected — tenant={}, connection={}",
                        tenantId, connectionId);
                adminSubscriptionManager.remove(connectionId, subscription);
            });
        });
    }
}
