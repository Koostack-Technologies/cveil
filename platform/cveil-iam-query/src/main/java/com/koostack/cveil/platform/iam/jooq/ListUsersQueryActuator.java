package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.IdpUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists all non-deleted users from the local IAM mirror table. */
public class ListUsersQueryActuator
        implements QueryActuator<ListUsersQueryActuator.ListUsersQuery, List<IdpUser>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListUsersQueryActuator.class);

    private static final org.jooq.Table<?> IAM_USER = table(name("cveiliam", "iam_user"));
    private static final org.jooq.Field<String> USERNAME = field(name("username"), String.class);
    private static final org.jooq.Field<String> EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<String> FULL_NAME = field(name("full_name"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    private final DSLContext dsl;

    public ListUsersQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Query input for listing users.
     *
     * @param realmId kept for API compatibility
     */
    public record ListUsersQuery(String realmId) {}

    @Override
    public List<IdpUser> execute(ListUsersQuery query) {
        LOG.debug(
                "Listing users (realmId='{}' ignored — reading from local IAM table)",
                query.realmId());
        return dsl.select(USERNAME, EMAIL, FULL_NAME, STATUS, CREATED_AT)
                .from(IAM_USER)
                .where(STATUS.ne("deleted"))
                .fetch(
                        r ->
                                new IdpUser(
                                        r.get(USERNAME),
                                        r.get(EMAIL),
                                        r.get(FULL_NAME),
                                        r.get(STATUS),
                                        r.get(CREATED_AT) != null
                                                ? r.get(CREATED_AT).toInstant()
                                                : null));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
