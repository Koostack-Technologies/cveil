package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.IdpUser;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that retrieves a single user by internal ID from the local IAM mirror table. */
public class GetUserQueryActuator
        implements QueryActuator<GetUserQueryActuator.GetUserQuery, IdpUser> {

    private static final Logger LOG = LoggerFactory.getLogger(GetUserQueryActuator.class);

    private static final org.jooq.Table<?> IAM_USER = table(name("platform_iam", "iam_user"));
    private static final org.jooq.Field<UUID> USER_ID = field(name("user_id"), UUID.class);
    private static final org.jooq.Field<String> USERNAME = field(name("username"), String.class);
    private static final org.jooq.Field<String> EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<String> FULL_NAME = field(name("full_name"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    private final DSLContext dsl;

    public GetUserQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Query input for getting a user by ID.
     *
     * @param realmId kept for API compatibility
     * @param userId the user's internal ID
     */
    public record GetUserQuery(String realmId, String userId) {}

    @Override
    public IdpUser execute(GetUserQuery query) {
        LOG.debug("Getting user by ID: userId={}", query.userId());
        Record r =
                dsl.select(USERNAME, EMAIL, FULL_NAME, STATUS, CREATED_AT)
                        .from(IAM_USER)
                        .where(USER_ID.eq(UUID.fromString(query.userId())))
                        .fetchOne();
        if (r == null) return null;
        return new IdpUser(
                r.get(USERNAME),
                r.get(EMAIL),
                r.get(FULL_NAME),
                r.get(STATUS),
                r.get(CREATED_AT) != null ? r.get(CREATED_AT).toInstant() : null);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
