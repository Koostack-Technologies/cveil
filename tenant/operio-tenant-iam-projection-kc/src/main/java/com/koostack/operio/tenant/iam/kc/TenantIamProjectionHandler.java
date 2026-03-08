package com.koostack.operio.tenant.iam.kc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant stream event handler — acknowledgement-only stub.
 *
 * <p>All IAM side-effects (Keycloak Admin API calls) are now performed synchronously inside the
 * actuators in the command module ({@code operio-tenant-iam-command}). This handler remains so that
 * replaying the tenant event stream does not trigger "unhandled event" warnings.
 *
 * <p>Each {@code handle*} method parses the payload for observability logging only.
 */
public class TenantIamProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TenantIamProjectionHandler.class);

    private final ObjectMapper mapper;

    public TenantIamProjectionHandler(Keycloak adminClient, String operioClientId) {
        // adminClient and operioClientId retained for constructor compatibility only
        this.mapper = new ObjectMapper();
    }

    /** Handle a tenant stream event. */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                case TenantIamEventTypes.USER_REGISTERED -> handleUserRegistered(payload);
                case TenantIamEventTypes.USER_SUSPENDED -> handleUserSuspended(payload);
                case TenantIamEventTypes.USER_ACTIVATED -> handleUserActivated(payload);
                case TenantIamEventTypes.SERVICE_ACCOUNT_CREATED -> handleServiceAccountCreated(
                        payload);
                case TenantIamEventTypes.SERVICE_ACCOUNT_REVOKED -> handleServiceAccountRevoked(
                        payload);
                case TenantIamEventTypes.ROLE_ASSIGNED -> handleRoleAssigned(payload);
                case TenantIamEventTypes.ROLE_REMOVED -> handleRoleRemoved(payload);
                default -> LOG.trace("Ignoring unhandled tenant event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.warn(
                    "Failed to parse tenant IAM event for logging: type={} messageId={}",
                    eventType,
                    messageId,
                    e);
        }
    }

    // -- No-op acknowledgement handlers (side-effects handled by actuators) ---

    private void handleUserRegistered(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "UserRegistered event acknowledged (actuator-handled): tenantId={}, email={}",
                node.path("tenantId").asText(),
                node.path("email").asText());
    }

    private void handleUserSuspended(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "UserSuspended event acknowledged (actuator-handled): tenantId={}, userId={}",
                node.path("tenantId").asText(),
                node.path("userId").asText());
    }

    private void handleUserActivated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "UserActivated event acknowledged (actuator-handled): tenantId={}, userId={}",
                node.path("tenantId").asText(),
                node.path("userId").asText());
    }

    private void handleServiceAccountCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "ServiceAccountCreated event acknowledged (actuator-handled): tenantId={},"
                        + " clientId={}",
                node.path("tenantId").asText(),
                node.path("clientId").asText());
    }

    private void handleServiceAccountRevoked(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "ServiceAccountRevoked event acknowledged (actuator-handled): tenantId={},"
                        + " clientId={}",
                node.path("tenantId").asText(),
                node.path("clientId").asText());
    }

    private void handleRoleAssigned(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "RoleAssigned event acknowledged (actuator-handled): tenantId={}, userId={},"
                        + " role={}",
                node.path("tenantId").asText(),
                node.path("userId").asText(),
                node.path("role").asText());
    }

    private void handleRoleRemoved(String payload) throws Exception {
        var node = mapper.readTree(payload);
        LOG.debug(
                "RoleRemoved event acknowledged (actuator-handled): tenantId={}, userId={},"
                        + " role={}",
                node.path("tenantId").asText(),
                node.path("userId").asText(),
                node.path("role").asText());
    }
}
