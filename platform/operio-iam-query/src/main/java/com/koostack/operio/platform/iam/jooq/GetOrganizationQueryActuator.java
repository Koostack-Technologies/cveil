package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.OrganizationInfo;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that gets a single Keycloak Organization by ID. */
public class GetOrganizationQueryActuator
        implements QueryActuator<
                GetOrganizationQueryActuator.GetOrganizationQuery, OrganizationInfo> {

    private static final Logger LOG = LoggerFactory.getLogger(GetOrganizationQueryActuator.class);

    private final KeycloakOrganizationService orgService;

    public GetOrganizationQueryActuator(KeycloakOrganizationService orgService) {
        this.orgService = orgService;
    }

    public record GetOrganizationQuery(String orgId) {}

    @Override
    public OrganizationInfo execute(GetOrganizationQuery query) {
        LOG.debug("Getting organization: orgId={}", query.orgId());
        OrganizationRepresentation org = orgService.getOrganization(query.orgId());
        return new OrganizationInfo(org.getId(), org.getAlias(), org.getName(), org.isEnabled());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
