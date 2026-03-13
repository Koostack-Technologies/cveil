package com.koostack.cveil.platform.iam.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform stream event handler for IAM domain events.
 *
 * <p>Materializes user, organization, membership, and invitation data from domain events into the
 * {@code platform_iam} read model tables. All operations are idempotent upserts so that replaying
 * the event stream produces the same result.
 *
 * <p>Keycloak side-effects are still performed synchronously by the command actuators before the
 * event is published (Phase 2 model). This handler only writes to the local database.
 */
public class IamProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IamProjectionHandler.class);

    // -- platform_iam.iam_user ------------------------------------------------
    private static final org.jooq.Table<?> IAM_USER = table(name("platform_iam", "iam_user"));
    private static final org.jooq.Field<UUID> USER_ID = field(name("user_id"), UUID.class);
    private static final org.jooq.Field<String> USER_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> USERNAME = field(name("username"), String.class);
    private static final org.jooq.Field<String> EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<String> FULL_NAME = field(name("full_name"), String.class);
    private static final org.jooq.Field<String> USER_STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<Boolean> ENABLED = field(name("enabled"), Boolean.class);
    private static final org.jooq.Field<String> DISPLAY_NAME_USER =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> AVATAR_URL =
            field(name("avatar_url"), String.class);
    private static final org.jooq.Field<String> LOCALE = field(name("locale"), String.class);
    private static final org.jooq.Field<String> TIMEZONE = field(name("timezone"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- platform_iam.iam_organization ----------------------------------------
    private static final org.jooq.Table<?> IAM_ORG =
            table(name("platform_iam", "iam_organization"));
    private static final org.jooq.Field<String> ORG_ID = field(name("org_id"), String.class);
    private static final org.jooq.Field<String> ORG_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> ORG_NAME = field(name("org_name"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> DESCRIPTION =
            field(name("description"), String.class);
    private static final org.jooq.Field<String> ORG_STATUS = field(name("status"), String.class);

    // -- platform_iam.org_membership ------------------------------------------
    private static final org.jooq.Table<?> MEMBERSHIP =
            table(name("platform_iam", "org_membership"));
    private static final org.jooq.Field<String> MEM_ORG_ID = field(name("org_id"), String.class);
    private static final org.jooq.Field<UUID> MEM_USER_ID = field(name("user_id"), UUID.class);
    private static final org.jooq.Field<String> MEM_ROLE = field(name("role"), String.class);
    private static final org.jooq.Field<OffsetDateTime> JOINED_AT =
            field(name("joined_at"), OffsetDateTime.class);

    // -- platform_iam.org_invitation ------------------------------------------
    private static final org.jooq.Table<?> INVITATION =
            table(name("platform_iam", "org_invitation"));
    private static final org.jooq.Field<String> INV_ORG_ID = field(name("org_id"), String.class);
    private static final org.jooq.Field<String> INV_EMAIL = field(name("email"), String.class);
    private static final org.jooq.Field<UUID> INV_INVITED_BY =
            field(name("invited_by"), UUID.class);
    private static final org.jooq.Field<String> INV_INTENDED_ROLE =
            field(name("intended_role"), String.class);
    private static final org.jooq.Field<String> INV_STATUS = field(name("status"), String.class);

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    /**
     * @param dsl jOOQ context for the platform database
     * @param adminClient retained for constructor compatibility with wiring code; not used
     */
    public IamProjectionHandler(DSLContext dsl, Keycloak adminClient) {
        this.dsl = dsl;
        this.mapper = new ObjectMapper();
    }

    /** Handle a platform stream event. */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                    // User lifecycle
                case IamEventTypes.USER_REGISTERED -> handleUserRegistered(payload);
                case IamEventTypes.USER_ACTIVATED -> handleUserActivated(payload);
                case IamEventTypes.USER_SUSPENDED -> handleUserSuspended(payload);
                case IamEventTypes.USER_DELETED -> handleUserDeleted(payload);
                case IamEventTypes.USER_PROFILE_UPDATED -> handleUserProfileUpdated(payload);
                case IamEventTypes.USER_APP_PROFILE_UPDATED -> handleUserAppProfileUpdated(payload);

                    // Organization lifecycle
                case IamEventTypes.ORGANIZATION_CREATED -> handleOrganizationCreated(payload);
                case IamEventTypes.ORGANIZATION_UPDATED -> handleOrganizationUpdated(payload);
                case IamEventTypes.ORGANIZATION_DELETED -> handleOrganizationDeleted(payload);

                    // Membership
                case IamEventTypes.ORGANIZATION_MEMBER_ADDED -> handleMemberAdded(payload);
                case IamEventTypes.ORGANIZATION_MEMBER_REMOVED -> handleMemberRemoved(payload);

                    // Invitations
                case IamEventTypes.ORGANIZATION_MEMBER_INVITED -> handleMemberInvited(payload);

                    // Events that don't need projection (identity/auth, tokens, etc.)
                case IamEventTypes.ACCESS_TOKEN_CREATED,
                        IamEventTypes.ACCESS_TOKEN_REVOKED,
                        IamEventTypes.ROLE_ASSIGNED,
                        IamEventTypes.ROLE_REMOVED,
                        IamEventTypes.USER_AUTHENTICATED,
                        IamEventTypes.TOKEN_REFRESHED,
                        IamEventTypes.USER_LOGGED_OUT,
                        IamEventTypes.PASSWORD_CHANGED,
                        IamEventTypes.REDIS_PROVISIONED -> LOG.trace(
                        "Acknowledged non-projected event: {}", eventType);

                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to project event: type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("IAM projection failed for " + eventType, e);
        }
    }

    // -----------------------------------------------------------------------
    // User handlers
    // -----------------------------------------------------------------------

    private void handleUserRegistered(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("userId").asText();
        String username = node.get("username").asText();
        String email = node.get("email").asText();
        String fullName =
                node.has("fullName") && !node.get("fullName").isNull()
                        ? node.get("fullName").asText()
                        : null;
        OffsetDateTime now = OffsetDateTime.now();

        // tenant_id for a user's personal tenant is derived from username
        // (matches RegisterUserOrchestrator which uses TenantIdDeriver.derive(username))
        String tenantId = deriveTenantId(username);

        dsl.insertInto(IAM_USER)
                .set(USER_ID, UUID.fromString(userId))
                .set(USER_TENANT_ID, tenantId)
                .set(USERNAME, username)
                .set(EMAIL, email)
                .set(FULL_NAME, fullName)
                .set(USER_STATUS, "active")
                .set(ENABLED, true)
                .set(CREATED_AT, now)
                .set(UPDATED_AT, now)
                .onConflict(USER_ID)
                .doUpdate()
                .set(USERNAME, username)
                .set(EMAIL, email)
                .set(FULL_NAME, fullName)
                .set(UPDATED_AT, now)
                .execute();

        LOG.debug("Projected UserRegistered: userId={}, username={}", userId, username);

        // "Role before existence" — fulfill pending invitations for this email
        fulfillPendingInvitations(UUID.fromString(userId), email, now);
    }

    private void handleUserActivated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("userId").asText();

        dsl.update(IAM_USER)
                .set(USER_STATUS, "active")
                .set(ENABLED, true)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(USER_ID.eq(UUID.fromString(userId)))
                .execute();
    }

    private void handleUserSuspended(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("userId").asText();

        dsl.update(IAM_USER)
                .set(USER_STATUS, "suspended")
                .set(ENABLED, false)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(USER_ID.eq(UUID.fromString(userId)))
                .execute();
    }

    private void handleUserDeleted(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("keycloakUserId").asText();

        dsl.update(IAM_USER)
                .set(USER_STATUS, "deleted")
                .set(ENABLED, false)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(USER_ID.eq(UUID.fromString(userId)))
                .execute();
    }

    private void handleUserProfileUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("userId").asText();

        var update = dsl.update(IAM_USER);
        var step = update.set(UPDATED_AT, OffsetDateTime.now());

        if (node.has("fullName") && !node.get("fullName").isNull()) {
            step = step.set(FULL_NAME, node.get("fullName").asText());
        }
        if (node.has("email") && !node.get("email").isNull()) {
            step = step.set(EMAIL, node.get("email").asText());
        }

        step.where(USER_ID.eq(UUID.fromString(userId))).execute();
    }

    private void handleUserAppProfileUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String userId = node.get("userId").asText();

        var update = dsl.update(IAM_USER);
        var step = update.set(UPDATED_AT, OffsetDateTime.now());

        if (node.has("displayName") && !node.get("displayName").isNull()) {
            step = step.set(DISPLAY_NAME_USER, node.get("displayName").asText());
        }
        if (node.has("avatarUrl") && !node.get("avatarUrl").isNull()) {
            step = step.set(AVATAR_URL, node.get("avatarUrl").asText());
        }
        if (node.has("locale") && !node.get("locale").isNull()) {
            step = step.set(LOCALE, node.get("locale").asText());
        }
        if (node.has("timezone") && !node.get("timezone").isNull()) {
            step = step.set(TIMEZONE, node.get("timezone").asText());
        }

        step.where(USER_ID.eq(UUID.fromString(userId))).execute();
        LOG.debug("Projected UserAppProfileUpdated: userId={}", userId);
    }

    // -----------------------------------------------------------------------
    // Organization handlers
    // -----------------------------------------------------------------------

    private void handleOrganizationCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();
        String orgName = node.get("orgName").asText();
        String displayName =
                node.has("displayName") && !node.get("displayName").isNull()
                        ? node.get("displayName").asText()
                        : orgName;
        OffsetDateTime now = OffsetDateTime.now();

        // tenant_id for an org is the orgName (matches RegisterOrganizationOrchestrator)
        String tenantId = deriveTenantId(orgName);

        dsl.insertInto(IAM_ORG)
                .set(ORG_ID, orgId)
                .set(ORG_TENANT_ID, tenantId)
                .set(ORG_NAME, orgName)
                .set(DISPLAY_NAME, displayName)
                .set(ORG_STATUS, "active")
                .set(CREATED_AT, now)
                .set(UPDATED_AT, now)
                .onConflict(ORG_ID)
                .doUpdate()
                .set(ORG_NAME, orgName)
                .set(DISPLAY_NAME, displayName)
                .set(UPDATED_AT, now)
                .execute();

        LOG.debug("Projected OrganizationCreated: orgId={}, orgName={}", orgId, orgName);
    }

    private void handleOrganizationUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();

        var step = dsl.update(IAM_ORG).set(UPDATED_AT, OffsetDateTime.now());

        if (node.has("displayName") && !node.get("displayName").isNull()) {
            step = step.set(DISPLAY_NAME, node.get("displayName").asText());
        }
        if (node.has("description") && !node.get("description").isNull()) {
            step = step.set(DESCRIPTION, node.get("description").asText());
        }

        step.where(ORG_ID.eq(orgId)).execute();
    }

    private void handleOrganizationDeleted(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();

        dsl.update(IAM_ORG)
                .set(ORG_STATUS, "deleted")
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(ORG_ID.eq(orgId))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Membership handlers
    // -----------------------------------------------------------------------

    private void handleMemberAdded(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();
        String userId = node.get("userId").asText();
        OffsetDateTime now = OffsetDateTime.now();

        // Check if there's a pending invitation with an intended role for this user+org
        String role = resolveIntendedRole(orgId, UUID.fromString(userId));

        dsl.insertInto(MEMBERSHIP)
                .set(MEM_ORG_ID, orgId)
                .set(MEM_USER_ID, UUID.fromString(userId))
                .set(MEM_ROLE, role)
                .set(JOINED_AT, now)
                .onConflict(MEM_ORG_ID, MEM_USER_ID)
                .doNothing()
                .execute();

        LOG.debug("Projected MemberAdded: orgId={}, userId={}, role={}", orgId, userId, role);
    }

    private void handleMemberRemoved(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();
        String userId = node.get("userId").asText();

        dsl.deleteFrom(MEMBERSHIP)
                .where(MEM_ORG_ID.eq(orgId).and(MEM_USER_ID.eq(UUID.fromString(userId))))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Invitation handlers
    // -----------------------------------------------------------------------

    private void handleMemberInvited(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String orgId = node.get("orgId").asText();
        String email = node.get("inviteeEmail").asText();
        String inviterUserId =
                node.has("inviterUserId") && !node.get("inviterUserId").isNull()
                        ? node.get("inviterUserId").asText()
                        : null;
        OffsetDateTime now = OffsetDateTime.now();

        var insert =
                dsl.insertInto(INVITATION)
                        .set(INV_ORG_ID, orgId)
                        .set(INV_EMAIL, email)
                        .set(INV_INTENDED_ROLE, "member")
                        .set(INV_STATUS, "pending")
                        .set(CREATED_AT, now);

        if (inviterUserId != null) {
            insert = insert.set(INV_INVITED_BY, UUID.fromString(inviterUserId));
        }

        insert.execute();

        LOG.debug("Projected MemberInvited: orgId={}, email={}", orgId, email);
    }

    // -----------------------------------------------------------------------
    // Invitation fulfillment ("role before existence")
    // -----------------------------------------------------------------------

    /**
     * When a user registers, check for pending org invitations matching their email. For each
     * pending invitation, auto-add the user to the org with the intended role and mark the
     * invitation as accepted.
     */
    private void fulfillPendingInvitations(UUID userId, String email, OffsetDateTime now) {
        var pending =
                dsl.select(INV_ORG_ID, INV_INTENDED_ROLE, field(name("invitation_id"), UUID.class))
                        .from(INVITATION)
                        .where(INV_EMAIL.eq(email).and(INV_STATUS.eq("pending")))
                        .fetch();

        if (pending.isEmpty()) {
            return;
        }

        LOG.info("Fulfilling {} pending invitation(s) for email={}", pending.size(), email);

        for (var row : pending) {
            String orgId = row.get(INV_ORG_ID);
            String role = row.get(INV_INTENDED_ROLE);
            UUID invitationId = row.get(field(name("invitation_id"), UUID.class));

            // Add membership with the pre-assigned role (update role if membership already exists)
            dsl.insertInto(MEMBERSHIP)
                    .set(MEM_ORG_ID, orgId)
                    .set(MEM_USER_ID, userId)
                    .set(MEM_ROLE, role != null ? role : "member")
                    .set(JOINED_AT, now)
                    .onConflict(MEM_ORG_ID, MEM_USER_ID)
                    .doUpdate()
                    .set(MEM_ROLE, role != null ? role : "member")
                    .execute();

            // Mark invitation as accepted
            dsl.update(INVITATION)
                    .set(INV_STATUS, "accepted")
                    .where(field(name("invitation_id"), UUID.class).eq(invitationId))
                    .execute();

            LOG.debug("Auto-enrolled userId={} in orgId={} with role={}", userId, orgId, role);
        }
    }

    /**
     * When a member is added to an org, check if there's a pending invitation that specifies an
     * intended role other than the default "member". Returns the intended role or "member".
     */
    private String resolveIntendedRole(String orgId, UUID userId) {
        // Look up the user's email to find matching invitations
        String email = dsl.select(EMAIL).from(IAM_USER).where(USER_ID.eq(userId)).fetchOne(EMAIL);

        if (email == null) {
            return "member";
        }

        // Find pending invitation for this org+email
        String intendedRole =
                dsl.select(INV_INTENDED_ROLE)
                        .from(INVITATION)
                        .where(
                                INV_ORG_ID
                                        .eq(orgId)
                                        .and(INV_EMAIL.eq(email))
                                        .and(INV_STATUS.eq("pending")))
                        .orderBy(CREATED_AT.desc())
                        .limit(1)
                        .fetchOne(INV_INTENDED_ROLE);

        if (intendedRole != null) {
            // Mark the invitation as accepted
            dsl.update(INVITATION)
                    .set(INV_STATUS, "accepted")
                    .where(
                            INV_ORG_ID
                                    .eq(orgId)
                                    .and(INV_EMAIL.eq(email))
                                    .and(INV_STATUS.eq("pending")))
                    .execute();
            return intendedRole;
        }

        return "member";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Derive a tenant ID from a name using the same logic as {@code TenantIdDeriver.derive()}.
     * Duplicated here to avoid a dependency on cveil-rest-api.
     */
    private static String deriveTenantId(String name) {
        String result =
                name.toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("^[0-9_]+", "")
                        .replaceAll("_+", "_");
        if (result.isEmpty()) {
            return name.toLowerCase();
        }
        return result.substring(0, Math.min(result.length(), 63));
    }
}
