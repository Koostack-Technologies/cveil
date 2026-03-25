package com.koostack.cveil.site.lifecycle;

import com.koostack.cveil.site.config.CveilConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of the bootstrap super-admin key.
 *
 * <p>The bootstrap key is a shared secret that grants super-admin access for initial platform setup
 * (creating the first tenant and admin user). In production, it should be removed from the
 * environment once real Keycloak users with admin roles exist.
 *
 * <p>With {@code cveil.super-admin.force-bootstrap=true} (used in integration tests), the bootstrap
 * key is always active regardless of platform state.
 *
 * @see com.koostack.cveil.site.filter.ApiKeyAuthFilter
 */
@ApplicationScoped
public class BootstrapKeyLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapKeyLifecycleService.class);

    @Inject CveilConfig config;

    /**
     * Returns whether the bootstrap key should be accepted for authentication.
     *
     * <p>The bootstrap key is active when a non-blank key is configured via {@code
     * cveil.super-admin.bootstrap-key}.
     */
    public boolean isBootstrapKeyActive() {
        return isBootstrapKeyConfigured();
    }

    /**
     * Returns whether the given token matches the configured bootstrap key AND the bootstrap key is
     * currently active.
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
                .flatMap(CveilConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .map(key -> key.equals(token))
                .orElse(false);
    }

    /**
     * Returns whether the given token matches the configured bootstrap key value regardless of
     * whether the key is currently active.
     *
     * @param token the bearer token to check
     * @return {@code true} if the token matches the bootstrap key value
     */
    public boolean isBootstrapKeyValue(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        return config.superAdmin()
                .flatMap(CveilConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .map(key -> key.equals(token))
                .orElse(false);
    }

    private boolean isBootstrapKeyConfigured() {
        return config.superAdmin()
                .flatMap(CveilConfig.SuperAdminConfig::bootstrapKey)
                .filter(key -> !key.isBlank())
                .isPresent();
    }
}
