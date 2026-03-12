package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSink;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSubscriber;
import com.koostack.operio.core.tenant.consent.invalidation.SubscriptionManager;
import com.koostack.operio.rest.auth.Permissions;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.context.OperioRequestHolder;
import com.koostack.operio.rest.interceptor.TenantScoped;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE publisher that reads cache invalidation messages from Redis Pub/Sub and publishes them to
 * connected clients via Server-Sent Events.
 *
 * <p>When an admin aggregate (Field, Purpose, Regulation, SubjectConsent) is modified, the Redis
 * projection publishes an invalidation message to the {@code
 * operio:consent-cache-invalidation:{tenantId}} Pub/Sub channel. This endpoint reads from that
 * channel and pushes each message to all subscribed SSE clients for the matching tenant.
 *
 * <p>Clients subscribe to this endpoint to receive real-time signals for refreshing their local
 * caches or UI.
 */
@Path("/api/v1/events")
@TenantScoped
@Tag(ref = OperioOpenApiConfig.TAG_ADMIN)
public class AdminEventsResource {

    private static final Logger log = LoggerFactory.getLogger(AdminEventsResource.class);

    @Inject
    @Named("adminSubscriptionManager")
    SubscriptionManager adminSubscriptionManager;

    @Inject OperioRequestHolder requestHolder;

    @GET
    @Path("/invalidations")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "adminEvents",
            summary = "Stream admin cache invalidation signals via SSE")
    public Multi<CacheInvalidationMessage> streamAdminEvents(@Context SecurityContext sc) {
        Permissions.require(requestHolder.authorization(), Permissions.EVENTS_READ);

        if (adminSubscriptionManager == null) {
            log.warn("SSE invalidation requested but Redis Pub/Sub publisher not available");
            return Multi.createFrom().empty();
        }

        String tenantId = TenantContext.require();
        String connectionId = UUID.randomUUID().toString();

        log.info(
                "SSE client connected for admin events — tenant={}, connection={}",
                tenantId,
                connectionId);

        return Multi.createFrom()
                .<CacheInvalidationMessage>emitter(
                        emitter -> {
                            InvalidationSink sink =
                                    message -> {
                                        if (!emitter.isCancelled()) {
                                            emitter.emit(message);
                                        }
                                    };

                            InvalidationSubscriber.Subscription subscription =
                                    adminSubscriptionManager.add(connectionId, tenantId, sink);

                            emitter.onTermination(
                                    () -> {
                                        log.info(
                                                "SSE client disconnected — tenant={},"
                                                        + " connection={}",
                                                tenantId,
                                                connectionId);
                                        adminSubscriptionManager.remove(connectionId, subscription);
                                    });
                        });
    }
}
