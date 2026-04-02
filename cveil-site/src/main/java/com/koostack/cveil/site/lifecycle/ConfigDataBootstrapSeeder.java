package com.koostack.cveil.site.lifecycle;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.core.platform.iam.events.RoleDefinitionCreatedEvent;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import com.koostack.cveil.core.platform.plan.events.PlanFeatureSetEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent startup task that backfills the event store with {@code iam.role-definition.created}
 * and {@code plan.feature.set} events so that the Redis site projection gets seeded with historical
 * configuration data.
 *
 * <p>Idempotency: if any {@code iam.role-definition.created} events already exist in the event
 * store, the seed is skipped entirely. This avoids duplicate events on subsequent restarts.
 *
 * <p>Errors on individual rows are logged and skipped — the seeder never aborts the full batch or
 * the application startup.
 */
public final class ConfigDataBootstrapSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigDataBootstrapSeeder.class);

    private static final String SENTINEL_SQL =
            "SELECT 1 FROM cveilevent.event WHERE event_type = 'iam.role-definition.created' LIMIT"
                    + " 1";

    private static final String ROLE_DEFINITIONS_SQL =
            "SELECT rd.role_id, rd.tenant_id, rd.scope, rd.display_name, rd.description,"
                    + " rd.is_system, rd.is_assignable"
                    + " FROM cveiliam.role_definition rd";

    private static final String ROLE_PERMISSIONS_SQL =
            "SELECT rp.permission_id FROM cveiliam.role_permission rp WHERE rp.role_id = ?";

    private static final String PLAN_FEATURES_SQL =
            "SELECT pf.plan_id, pf.feature_key, pf.enabled, pf.limit_value"
                    + " FROM cveilcore.plan_feature pf";

    private final DataSource dataSource;
    private final EventService<?> eventService;

    public ConfigDataBootstrapSeeder(DataSource dataSource, EventService<?> eventService) {
        this.dataSource = dataSource;
        this.eventService = eventService;
    }

    /** Run the idempotent backfill. Safe to call on every startup. */
    public void seed() {
        if (alreadySeeded()) {
            LOG.info("Bootstrap seeder: already seeded — skipping");
            return;
        }

        int roleCount = seedRoleDefinitions();
        int featureCount = seedPlanFeatures();

        LOG.info(
                "Bootstrap seeder: published {} role definitions, {} plan features",
                roleCount,
                featureCount);
    }

    // -- Sentinel check --------------------------------------------------------

    private boolean alreadySeeded() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(SENTINEL_SQL);
                ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (Exception e) {
            LOG.warn(
                    "Bootstrap seeder: sentinel check failed, proceeding with seed: {}",
                    e.getMessage());
            return false;
        }
    }

    // -- Role definitions ------------------------------------------------------

    private int seedRoleDefinitions() {
        int published = 0;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(ROLE_DEFINITIONS_SQL);
                ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String roleId = rs.getString("role_id");
                try {
                    String tenantId = rs.getString("tenant_id");
                    String scope = rs.getString("scope");
                    String displayName = rs.getString("display_name");
                    String description = rs.getString("description");
                    boolean isSystem = rs.getBoolean("is_system");
                    boolean isAssignable = rs.getBoolean("is_assignable");

                    List<String> permissions = loadPermissions(roleId);

                    RoleDefinitionCreatedEvent event =
                            new RoleDefinitionCreatedEvent(
                                    roleId,
                                    tenantId,
                                    scope,
                                    displayName,
                                    description,
                                    isSystem,
                                    isAssignable,
                                    permissions);

                    eventService.publishEvent(
                            PublishDomainEventInput.of(
                                    PlatformConstants.PLATFORM_TENANT_ID,
                                    IamEventTypes.ROLE_DEFINITION_CREATED,
                                    new AggregateKey(
                                            UUID.nameUUIDFromBytes(roleId.getBytes()),
                                            "role-definition",
                                            PlatformConstants.PLATFORM_TENANT_ID),
                                    event));
                    published++;
                } catch (Exception e) {
                    LOG.warn(
                            "Bootstrap seeder: failed to publish role definition '{}': {}",
                            roleId,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Bootstrap seeder: failed to read role definitions: {}", e.getMessage());
        }
        return published;
    }

    private List<String> loadPermissions(String roleId) {
        List<String> permissions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(ROLE_PERMISSIONS_SQL)) {
            ps.setString(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    permissions.add(rs.getString("permission_id"));
                }
            }
        } catch (Exception e) {
            LOG.warn(
                    "Bootstrap seeder: failed to load permissions for role '{}': {}",
                    roleId,
                    e.getMessage());
        }
        return permissions;
    }

    // -- Plan features ---------------------------------------------------------

    private int seedPlanFeatures() {
        int published = 0;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(PLAN_FEATURES_SQL);
                ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String planId = rs.getString("plan_id");
                String featureKey = rs.getString("feature_key");
                try {
                    boolean enabled = rs.getBoolean("enabled");
                    int limitRaw = rs.getInt("limit_value");
                    Integer limitValue = rs.wasNull() ? null : limitRaw;

                    PlanFeatureSetEvent event =
                            new PlanFeatureSetEvent(planId, featureKey, enabled, limitValue);

                    eventService.publishEvent(
                            PublishDomainEventInput.of(
                                    PlatformConstants.PLATFORM_TENANT_ID,
                                    PlanEventTypes.PLAN_FEATURE_SET,
                                    new AggregateKey(
                                            UUID.nameUUIDFromBytes(planId.getBytes()),
                                            "plan",
                                            PlatformConstants.PLATFORM_TENANT_ID),
                                    event));
                    published++;
                } catch (Exception e) {
                    LOG.warn(
                            "Bootstrap seeder: failed to publish plan feature '{}/{}': {}",
                            planId,
                            featureKey,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Bootstrap seeder: failed to read plan features: {}", e.getMessage());
        }
        return published;
    }
}
