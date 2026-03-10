package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.OrganizationInfo;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists all Keycloak Organizations (tenants). */
public class ListOrganizationsQueryActuator
        implements QueryActuator<
                ListOrganizationsQueryActuator.ListOrganizationsQuery, List<OrganizationInfo>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListOrganizationsQueryActuator.class);

    private final KeycloakOrganizationService orgService;

    public ListOrganizationsQueryActuator(KeycloakOrganizationService orgService) {
        this.orgService = orgService;
    }

    public record ListOrganizationsQuery(int offset, int limit) {}

    @Override
    public List<OrganizationInfo> execute(ListOrganizationsQuery query) {
        LOG.debug("Listing organizations: offset={}, limit={}", query.offset(), query.limit());
        return orgService.listOrganizations(query.offset(), query.limit()).stream()
                .map(
                        org ->
                                new OrganizationInfo(
                                        org.getId(),
                                        org.getName(),
                                        org.getAlias(),
                                        org.isEnabled()))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
