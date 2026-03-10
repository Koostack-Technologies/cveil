package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.OrgMemberInfo;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists members of a Keycloak Organization. */
public class ListOrgMembersQueryActuator
        implements QueryActuator<
                ListOrgMembersQueryActuator.ListOrgMembersQuery, List<OrgMemberInfo>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListOrgMembersQueryActuator.class);

    private final KeycloakOrganizationService orgService;

    public ListOrgMembersQueryActuator(KeycloakOrganizationService orgService) {
        this.orgService = orgService;
    }

    public record ListOrgMembersQuery(String orgId, int offset, int limit) {}

    @Override
    public List<OrgMemberInfo> execute(ListOrgMembersQuery query) {
        LOG.debug(
                "Listing org members: orgId={}, offset={}, limit={}",
                query.orgId(),
                query.offset(),
                query.limit());
        return orgService.listMembers(query.orgId(), query.offset(), query.limit()).stream()
                .map(
                        member ->
                                new OrgMemberInfo(
                                        member.getId(), member.getUsername(), member.getEmail()))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
