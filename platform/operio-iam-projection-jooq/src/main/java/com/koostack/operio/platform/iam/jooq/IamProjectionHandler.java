package com.koostack.operio.platform.iam.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.*;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform stream event handler for IAM domain events.
 *
 * <p>Since Phase 2, all Keycloak side-effects are performed synchronously by the actuators before
 * the event is published. This handler is retained so that replaying the event stream does not
 * trigger "unhandled event" warnings — every handler method is a no-op acknowledgement.
 */
public class IamProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IamProjectionHandler.class);

    private final ObjectMapper mapper;

    /**
     * @param adminClient retained for constructor compatibility with wiring code; not used
     */
    public IamProjectionHandler(Keycloak adminClient) {
        this.mapper = new ObjectMapper();
    }

    /** Handle a platform stream event. */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                case IamEventTypes.USER_REGISTERED -> acknowledgeEvent("UserRegistered", payload);
                case IamEventTypes.USER_SUSPENDED -> acknowledgeEvent("UserSuspended", payload);
                case IamEventTypes.USER_ACTIVATED -> acknowledgeEvent("UserActivated", payload);
                case IamEventTypes.SERVICE_ACCOUNT_CREATED -> acknowledgeEvent(
                        "ServiceAccountCreated", payload);
                case IamEventTypes.SERVICE_ACCOUNT_REVOKED -> acknowledgeEvent(
                        "ServiceAccountRevoked", payload);
                case IamEventTypes.ROLE_ASSIGNED -> acknowledgeEvent("RoleAssigned", payload);
                case IamEventTypes.ROLE_REMOVED -> acknowledgeEvent("RoleRemoved", payload);
                case IamEventTypes.API_KEY_GENERATED -> LOG.debug(
                        "Ignoring legacy api-key.generated event");
                case IamEventTypes.API_KEY_REVOKED -> LOG.debug(
                        "Ignoring legacy api-key.revoked event");
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to acknowledge event: type={} messageId={}", eventType, messageId, e);
        }
    }

    /**
     * No-op acknowledgement — the actuator already performed the Keycloak side-effect before
     * publishing the event. This handler only logs for observability.
     */
    private void acknowledgeEvent(String eventName, String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "{} event acknowledged (actuator-handled): realmId={}",
                eventName,
                node.path("realmId").asText());
    }
}
