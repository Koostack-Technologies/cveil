package com.koostack.operio.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.OrgMemberInfo;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists members of an organization from the local IAM mirror tables. */
public class ListOrgMembersQueryActuator
        implements QueryActuator<
                ListOrgMembersQueryActuator.ListOrgMembersQuery, List<OrgMemberInfo>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListOrgMembersQueryActuator.class);

    private static final org.jooq.Table<?> ORG_MEMBERSHIP =
            table(name("platform_iam", "org_membership"));
    private static final org.jooq.Table<?> IAM_USER = table(name("platform_iam", "iam_user"));
    private static final org.jooq.Field<String> M_ORG_ID = field(name("m", "org_id"), String.class);
    private static final org.jooq.Field<UUID> M_USER_ID = field(name("m", "user_id"), UUID.class);
    private static final org.jooq.Field<UUID> U_USER_ID = field(name("u", "user_id"), UUID.class);
    private static final org.jooq.Field<String> U_USERNAME =
            field(name("u", "username"), String.class);
    private static final org.jooq.Field<String> U_EMAIL = field(name("u", "email"), String.class);

    private final DSLContext dsl;

    public ListOrgMembersQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record ListOrgMembersQuery(String orgId, int offset, int limit) {}

    @Override
    public List<OrgMemberInfo> execute(ListOrgMembersQuery query) {
        LOG.debug(
                "Listing org members: orgId={}, offset={}, limit={}",
                query.orgId(),
                query.offset(),
                query.limit());
        return dsl.select(U_USER_ID, U_USERNAME, U_EMAIL)
                .from(ORG_MEMBERSHIP.as("m"))
                .join(IAM_USER.as("u"))
                .on(M_USER_ID.eq(U_USER_ID))
                .where(M_ORG_ID.eq(query.orgId()))
                .offset(query.offset())
                .limit(query.limit())
                .fetch(
                        r ->
                                new OrgMemberInfo(
                                        r.get(U_USER_ID).toString(),
                                        r.get(U_USERNAME),
                                        r.get(U_EMAIL)));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
