package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.iam.IamQueryService.OrganizationInfo;
import java.util.List;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists organizations from the local IAM mirror table. */
public class ListOrganizationsQueryActuator
        implements QueryActuator<
                ListOrganizationsQueryActuator.ListOrganizationsQuery, List<OrganizationInfo>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListOrganizationsQueryActuator.class);

    private static final org.jooq.Table<?> IAM_ORGANIZATION =
            table(name("platform_iam", "iam_organization"));
    private static final org.jooq.Field<String> ORG_ID = field(name("org_id"), String.class);
    private static final org.jooq.Field<String> ORG_NAME = field(name("org_name"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);

    private final DSLContext dsl;

    public ListOrganizationsQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record ListOrganizationsQuery(int offset, int limit) {}

    @Override
    public List<OrganizationInfo> execute(ListOrganizationsQuery query) {
        LOG.debug("Listing organizations: offset={}, limit={}", query.offset(), query.limit());
        return dsl.select(ORG_ID, ORG_NAME, DISPLAY_NAME, STATUS)
                .from(IAM_ORGANIZATION)
                .where(STATUS.ne("deleted"))
                .offset(query.offset())
                .limit(query.limit())
                .fetch(
                        r ->
                                new OrganizationInfo(
                                        r.get(ORG_ID),
                                        r.get(ORG_NAME),
                                        r.get(DISPLAY_NAME),
                                        "active".equals(r.get(STATUS))));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
