package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.IdpUser;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that finds a user by username from the local IAM mirror table. */
public class FindUserByUsernameQueryActuator
        implements QueryActuator<FindUserByUsernameQueryActuator.FindUserByUsernameQuery, IdpUser> {

    private static final Logger LOG =
            LoggerFactory.getLogger(FindUserByUsernameQueryActuator.class);

    private static final org.jooq.Table<?> IAM_USER = table(name("cveiliam", "iam_user"));
    private static final org.jooq.Field<String> USERNAME = field(name("username"), String.class);
    private static final org.jooq.Field<String> EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<String> FULL_NAME = field(name("full_name"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    private final DSLContext dsl;

    public FindUserByUsernameQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Query input for finding a user by username.
     *
     * @param realmId kept for API compatibility
     * @param username the username to search for
     */
    public record FindUserByUsernameQuery(String realmId, String username) {}

    @Override
    public IdpUser execute(FindUserByUsernameQuery query) {
        LOG.debug("Finding user by username: {}", query.username());
        Record r =
                dsl.select(USERNAME, EMAIL, FULL_NAME, STATUS, CREATED_AT)
                        .from(IAM_USER)
                        .where(USERNAME.eq(query.username()))
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
