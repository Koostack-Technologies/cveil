package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that returns the count of members in an organization.
 *
 * <p>Uses {@code SELECT COUNT(*)} instead of fetching full member rows, which is significantly more
 * efficient for limit checking where only the count matters.
 */
public class CountOrgMembersQueryActuator
        implements QueryActuator<CountOrgMembersQueryActuator.CountOrgMembersQuery, Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(CountOrgMembersQueryActuator.class);

    private static final org.jooq.Table<?> ORG_MEMBERSHIP =
            table(name("cveiliam", "org_membership"));
    private static final org.jooq.Field<String> F_ORG_ID = field(name("org_id"), String.class);

    private final DSLContext dsl;

    public CountOrgMembersQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record CountOrgMembersQuery(String orgId) {}

    @Override
    public Integer execute(CountOrgMembersQuery query) {
        LOG.debug("Counting org members: orgId={}", query.orgId());
        return dsl.selectCount()
                .from(ORG_MEMBERSHIP)
                .where(F_ORG_ID.eq(query.orgId()))
                .fetchOne(0, int.class);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
