package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.iam.IamQueryService.OrganizationInfo;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hybrid query actuator — reads user profile and org memberships from the local IAM mirror tables,
 * but still fetches realm-level roles from the Keycloak Admin API.
 */
public class GetCurrentUserQueryActuator
        implements QueryActuator<
                GetCurrentUserQueryActuator.GetCurrentUserQuery,
                GetCurrentUserQueryActuator.CurrentUserResult> {

    private static final Logger LOG = LoggerFactory.getLogger(GetCurrentUserQueryActuator.class);

    // -- user table fields --
    private static final org.jooq.Table<?> IAM_USER = table(name("platform_iam", "iam_user"));
    private static final org.jooq.Field<UUID> USER_ID = field(name("user_id"), UUID.class);
    private static final org.jooq.Field<String> USERNAME = field(name("username"), String.class);
    private static final org.jooq.Field<String> EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<String> FULL_NAME = field(name("full_name"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    // -- org join fields --
    private static final org.jooq.Table<?> ORG_MEMBERSHIP =
            table(name("platform_iam", "org_membership"));
    private static final org.jooq.Table<?> IAM_ORGANIZATION =
            table(name("platform_iam", "iam_organization"));
    private static final org.jooq.Field<UUID> M_USER_ID = field(name("m", "user_id"), UUID.class);
    private static final org.jooq.Field<String> M_ORG_ID = field(name("m", "org_id"), String.class);
    private static final org.jooq.Field<String> O_ORG_ID = field(name("o", "org_id"), String.class);
    private static final org.jooq.Field<String> O_ORG_NAME =
            field(name("o", "org_name"), String.class);
    private static final org.jooq.Field<String> O_DISPLAY_NAME =
            field(name("o", "display_name"), String.class);
    private static final org.jooq.Field<String> O_STATUS = field(name("o", "status"), String.class);

    private final DSLContext dsl;
    private final Keycloak adminClient;
    private final KeycloakConfig keycloakConfig;

    public GetCurrentUserQueryActuator(
            DSLContext dsl, Keycloak adminClient, KeycloakConfig keycloakConfig) {
        this.dsl = dsl;
        this.adminClient = adminClient;
        this.keycloakConfig = keycloakConfig;
    }

    /**
     * Query input for getting the current user's profile.
     *
     * @param userId the Keycloak user ID (JWT sub claim)
     */
    public record GetCurrentUserQuery(String userId) {}

    /**
     * Result containing user profile and org memberships.
     *
     * @param userId the user's ID
     * @param username the user's username
     * @param email the user's email
     * @param fullName the user's full name
     * @param roles the user's realm roles (from Keycloak)
     * @param status active or suspended
     * @param createdAt when the user was created
     * @param organizations list of orgs the user belongs to
     */
    public record CurrentUserResult(
            String userId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            String status,
            java.time.Instant createdAt,
            List<OrganizationInfo> organizations) {}

    @Override
    public CurrentUserResult execute(GetCurrentUserQuery query) {
        LOG.debug("Getting current user: userId={}", query.userId());

        UUID uid = UUID.fromString(query.userId());

        // 1. Read user profile from local DB
        Record userRow =
                dsl.select(USERNAME, EMAIL, FULL_NAME, STATUS, CREATED_AT)
                        .from(IAM_USER)
                        .where(USER_ID.eq(uid))
                        .fetchOne();
        if (userRow == null) {
            return null;
        }

        // 2. Roles still come from Keycloak
        Set<String> roles;
        try {
            roles =
                    adminClient
                            .realm(keycloakConfig.cveilRealmName())
                            .users()
                            .get(query.userId())
                            .roles()
                            .realmLevel()
                            .listEffective()
                            .stream()
                            .map(RoleRepresentation::getName)
                            .collect(Collectors.toSet());
        } catch (Exception e) {
            LOG.warn("Failed to fetch roles for user {}: {}", query.userId(), e.getMessage());
            roles = Set.of();
        }

        // 3. Org memberships from local DB
        List<OrganizationInfo> organizations;
        try {
            organizations =
                    dsl.select(O_ORG_ID, O_ORG_NAME, O_DISPLAY_NAME, O_STATUS)
                            .from(ORG_MEMBERSHIP.as("m"))
                            .join(IAM_ORGANIZATION.as("o"))
                            .on(M_ORG_ID.eq(O_ORG_ID))
                            .where(M_USER_ID.eq(uid))
                            .fetch(
                                    r ->
                                            new OrganizationInfo(
                                                    r.get(O_ORG_ID),
                                                    r.get(O_ORG_NAME),
                                                    r.get(O_DISPLAY_NAME),
                                                    "active".equals(r.get(O_STATUS))));
        } catch (Exception e) {
            LOG.warn("Failed to list orgs for user {}: {}", query.userId(), e.getMessage());
            organizations = List.of();
        }

        OffsetDateTime createdOdt = userRow.get(CREATED_AT);
        return new CurrentUserResult(
                query.userId(),
                userRow.get(USERNAME),
                userRow.get(EMAIL),
                userRow.get(FULL_NAME),
                roles,
                userRow.get(STATUS),
                createdOdt != null ? createdOdt.toInstant() : null,
                organizations);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
