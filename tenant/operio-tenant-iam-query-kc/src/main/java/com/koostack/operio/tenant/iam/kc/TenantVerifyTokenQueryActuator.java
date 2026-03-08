package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that verifies a JWT access token for a tenant and returns the identity context.
 */
public class TenantVerifyTokenQueryActuator
        implements QueryActuator<
                TenantVerifyTokenQueryActuator.TenantVerifyTokenQuery, IdentityContext> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantVerifyTokenQueryActuator.class);

    private final JwksTokenVerifier jwksVerifier;
    private final KeycloakConfig config;

    public TenantVerifyTokenQueryActuator(JwksTokenVerifier jwksVerifier, KeycloakConfig config) {
        this.jwksVerifier = jwksVerifier;
        this.config = config;
    }

    /**
     * Query input for verifying a tenant JWT access token.
     *
     * @param tenantId    the tenant identifier
     * @param accessToken the JWT access token
     */
    public record TenantVerifyTokenQuery(String tenantId, String accessToken) {}

    @Override
    public IdentityContext execute(TenantVerifyTokenQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        String jwksUrl =
                config.serverUrl()
                        + "/realms/"
                        + realmId
                        + "/protocol/openid-connect/certs";
        return jwksVerifier.verify(jwksUrl, query.accessToken(), realmId);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
