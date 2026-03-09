package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that verifies a JWT access token and returns the identity context. */
public class VerifyTokenQueryActuator
        implements QueryActuator<VerifyTokenQueryActuator.VerifyTokenQuery, IdentityContext> {

    private static final Logger LOG = LoggerFactory.getLogger(VerifyTokenQueryActuator.class);

    private final JwksTokenVerifier jwksVerifier;
    private final KeycloakConfig config;

    public VerifyTokenQueryActuator(JwksTokenVerifier jwksVerifier, KeycloakConfig config) {
        this.jwksVerifier = jwksVerifier;
        this.config = config;
    }

    /**
     * Query input for verifying a JWT access token.
     *
     * @param realmId the Keycloak realm
     * @param accessToken the JWT access token
     */
    public record VerifyTokenQuery(String realmId, String accessToken) {}

    @Override
    public IdentityContext execute(VerifyTokenQuery query) {
        String jwksUrl =
                config.serverUrl()
                        + "/realms/"
                        + query.realmId()
                        + "/protocol/openid-connect/certs";
        return jwksVerifier.verify(jwksUrl, query.accessToken(), query.realmId());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
