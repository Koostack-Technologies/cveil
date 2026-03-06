package com.koostack.operio.rest.lifecycle;

import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of the bootstrap super-admin key.
 *
 * <p>The bootstrap key ({@code OPERIO_BOOTSTRAP_KEY}) is a one-time-use secret that allows the very
 * first API call to create the initial tenant and admin API key before any regular keys exist. Once
 * the platform has been bootstrapped (i.e. at least one real API key exists), the bootstrap key
 * should be removed from the environment for security.
 *
 * <h2>Lifecycle Stages</h2>
 *
 * <ol>
 *   <li><strong>Fresh deployment</strong> — no tenants, no API keys. Bootstrap key is active and
 *       required to perform initial setup.
 *   <li><strong>First API key created</strong> — the bootstrap key is auto-disabled for the
 *       remainder of this process's lifetime. Subsequent requests with the bootstrap key are
 *       rejected with HTTP 403 and a message instructing the operator to use a real API key.
 *   <li><strong>Subsequent restarts</strong> — if the bootstrap key env var is still set and real
 *       API keys exist, a {@code WARN}-level log message is emitted at startup urging the operator
 *       to remove the env var. The bootstrap key remains auto-disabled unless force-override is
 *       set.
 * </ol>
 *
 * <h2>Auto-Disable Behavior</h2>
 *
 * <p>Once any API key is created via the platform endpoints, the bootstrap key is automatically
 * disabled for the lifetime of the current JVM process. This provides a default-secure posture:
 * even if the operator forgets to remove the env var, the bootstrap key cannot be used to gain
 * super-admin access once real credentials exist.
 *
 * <h2>Force Override</h2>
 *
 * <p>In exceptional situations (e.g. all API keys were accidentally revoked, disaster recovery),
 * the operator can set {@code OPERIO_FORCE_BOOTSTRAP=true} to re-enable the bootstrap key even
 * after auto-disable. This is a deliberate escape hatch that requires explicit operator intent and
 * is logged at {@code WARN} level.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The auto-disable flag is an {@link AtomicBoolean}. State transitions are monotonic (active →
 * disabled) and never reversed at runtime, so there is no ABA risk or need for complex
 * synchronization.
 *
 * @see com.koostack.operio.rest.filter.ApiKeyAuthFilter
 * @see com.koostack.operio.rest.resource.PlatformResource
 */
@ApplicationScoped
public class BootstrapKeyLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapKeyLifecycleService.class);

    /**
     * Monotonic flag: once set to {@code true}, the bootstrap key is considered consumed and will
     * not be accepted by the auth filter (unless force-override is active).
     */
    private final AtomicBoolean bootstrapConsumed = new AtomicBoolean(false);

    @Inject OperioConfig config;

    @Inject PlatformCqrsHolder cqrs;

    // -----------------------------------------------------------------------
    // Startup check
    // -----------------------------------------------------------------------

    /**
     * Runs once at application startup to inspect bootstrap key state.
     *
     * <p>If the bootstrap key is configured and real API keys already exist on the platform, a
     * warning is logged. If the platform has existing API keys, the bootstrap key is also
     * auto-disabled immediately (no need to wait for the first API key creation in this process).
     *
     * <p>If query services are not available (embedded mode), the check is skipped entirely —
     * embedded mode does not support multi-tenant management or API key authentication.
     *
     * @param event the Quarkus startup event (unused, required by CDI)
     */
    void onStartup(@Observes StartupEvent event) {
        if (!isBootstrapKeyConfigured()) {
            LOG.info(
                    "Bootstrap key lifecycle: no bootstrap key configured — "
                            + "bootstrap authentication is disabled");
            return;
        }

        if (isForceBootstrapEnabled()) {
            LOG.warn(
                    "Bootstrap key lifecycle: OPERIO_FORCE_BOOTSTRAP=true — "
                            + "bootstrap key is FORCE-ENABLED. This overrides "
                            + "auto-disable and should only be used for disaster "
                            + "recovery. Remove this env var after re-establishing "
                            + "real API keys.");
        }

        if (!cqrs.isCqrsAvailable()) {
            LOG.debug(
                    "Bootstrap key lifecycle: query services not available "
                            + "(embedded mode) — skipping startup API key check");
            return;
        }

        // Check if any real API keys exist on the platform
        try {
            boolean apiKeysExist = checkForExistingApiKeys();

            if (apiKeysExist) {
                LOG.warn(
                        "Bootstrap key lifecycle: OPERIO_BOOTSTRAP_KEY is still "
                                + "set but real API keys already exist on the platform. "
                                + "Remove the OPERIO_BOOTSTRAP_KEY environment variable "
                                + "for production security. The bootstrap key has been "
                                + "auto-disabled for this process.");
                bootstrapConsumed.set(true);
            } else {
                LOG.info(
                        "Bootstrap key lifecycle: no existing API keys found — "
                                + "bootstrap key is active for initial platform setup");
            }
        } catch (Exception e) {
            // Don't fail startup if the check fails (e.g. platform DB
            // not yet migrated, connection issue). Log and leave the
            // bootstrap key in its default state (active).
            LOG.warn(
                    "Bootstrap key lifecycle: failed to check for existing API "
                            + "keys during startup — bootstrap key remains in its "
                            + "default state. Error: {}",
                    e.getMessage());
            LOG.debug("Bootstrap key lifecycle: startup check error details", e);
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns whether the bootstrap key should be accepted for authentication.
     *
     * <p>The bootstrap key is considered active when ALL of the following are true:
     *
     * <ol>
     *   <li>A non-blank bootstrap key is configured via {@code OPERIO_BOOTSTRAP_KEY}
     *   <li>The key has not been auto-disabled (consumed), OR {@code OPERIO_FORCE_BOOTSTRAP=true}
     *       overrides the auto-disable
     * </ol>
     *
     * @return {@code true} if the bootstrap key should be accepted
     */
    public boolean isBootstrapKeyActive() {
        if (!isBootstrapKeyConfigured()) {
            return false;
        }

        // Force override always wins
        if (isForceBootstrapEnabled()) {
            return true;
        }

        // If consumed (auto-disabled), the key is inactive
        return !bootstrapConsumed.get();
    }

    /**
     * Returns whether the given token matches the configured bootstrap key AND the bootstrap key is
     * currently active (not auto-disabled).
     *
     * <p>This is the primary method called by {@code ApiKeyAuthFilter} to determine if a bearer
     * token is the bootstrap key and should be granted super-admin access.
     *
     * @param token the bearer token to check
     * @return {@code true} if the token is the active bootstrap key
     */
    public boolean isActiveBootstrapKey(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        if (!isBootstrapKeyActive()) {
            return false;
        }

        return config.superAdmin()
                .flatMap(OperioConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .map(key -> key.equals(token))
                .orElse(false);
    }

    /**
     * Returns whether the given token matches the configured bootstrap key regardless of whether
     * the key is currently active or consumed.
     *
     * <p>Used by the auth filter to distinguish between "invalid token" (401) and "bootstrap key is
     * disabled" (403) for better error messages.
     *
     * @param token the bearer token to check
     * @return {@code true} if the token matches the bootstrap key value
     */
    public boolean isBootstrapKeyValue(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        return config.superAdmin()
                .flatMap(OperioConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .map(key -> key.equals(token))
                .orElse(false);
    }

    /**
     * Marks the bootstrap key as consumed (auto-disabled).
     *
     * <p>Called by {@code PlatformResource} after successfully creating an API key. Once consumed,
     * the bootstrap key is rejected by the auth filter unless {@code OPERIO_FORCE_BOOTSTRAP=true}
     * is set.
     *
     * <p>This operation is idempotent — calling it multiple times has no additional effect.
     */
    public void markBootstrapConsumed() {
        boolean wasActive = bootstrapConsumed.compareAndSet(false, true);
        if (wasActive) {
            LOG.info(
                    "Bootstrap key lifecycle: bootstrap key has been auto-disabled "
                            + "after API key creation. Remove the OPERIO_BOOTSTRAP_KEY "
                            + "environment variable and restart for clean production "
                            + "security posture.");

            if (isForceBootstrapEnabled()) {
                LOG.warn(
                        "Bootstrap key lifecycle: OPERIO_FORCE_BOOTSTRAP=true is "
                                + "set — the bootstrap key will continue to be "
                                + "accepted despite auto-disable. Remove this env var "
                                + "once platform setup is complete.");
            }
        } else {
            LOG.debug(
                    "Bootstrap key lifecycle: markBootstrapConsumed() called "
                            + "but key was already consumed — no state change");
        }
    }

    /**
     * Returns whether the bootstrap key has been consumed (auto-disabled), regardless of
     * force-override state.
     *
     * <p>Useful for diagnostic/health endpoints that want to report bootstrap key status.
     *
     * @return {@code true} if the bootstrap key has been consumed
     */
    public boolean isBootstrapConsumed() {
        return bootstrapConsumed.get();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Checks whether a non-blank bootstrap key is configured.
     *
     * @return {@code true} if a bootstrap key value is present and non-blank
     */
    private boolean isBootstrapKeyConfigured() {
        return config.superAdmin()
                .flatMap(OperioConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .isPresent();
    }

    /**
     * Checks whether the force-bootstrap override is enabled.
     *
     * @return {@code true} if {@code operio.super-admin.force-bootstrap=true}
     */
    private boolean isForceBootstrapEnabled() {
        return config.superAdmin().map(OperioConfig.SuperAdminConfig::forceBootstrap).orElse(false);
    }

    /**
     * Iterates tenants and checks whether at least one active API key exists on the platform.
     *
     * <p>Short-circuits on the first key found. Revoked keys are excluded — only {@code ACTIVE}
     * keys count as evidence that the platform has been bootstrapped.
     *
     * @return {@code true} if at least one active API key exists
     */
    @SuppressWarnings("unchecked")
    private boolean checkForExistingApiKeys() {
        if (!cqrs.isCqrsAvailable()) {
            return false;
        }
        List<TenantInfo> tenants = cqrs.coreQueryService().listTenants();
        if (tenants == null || tenants.isEmpty()) {
            return false;
        }

        for (TenantInfo tenant : tenants) {
            List<IamQueryService.ApiKeyInfo> keys =
                    cqrs.iamQueryService().listApiKeys(tenant.tenantId());
            if (keys != null) {
                boolean hasActiveKey =
                        keys.stream().anyMatch(key -> "ACTIVE".equalsIgnoreCase(key.status()));
                if (hasActiveKey) {
                    LOG.debug(
                            "Bootstrap key lifecycle: found active API key for "
                                    + "tenant '{}' — platform has been bootstrapped",
                            tenant.tenantId());
                    return true;
                }
            }
        }

        return false;
    }
}
