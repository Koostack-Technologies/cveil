package com.koostack.cveil.rest.onboarding;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side holder for onboarding wizard state, keyed by tenant ID.
 *
 * <p>Follows the same pattern as {@link com.koostack.cveil.rest.lifecycle.ProvisioningTracker}:
 * in-memory {@link ConcurrentHashMap} with lazy TTL-based expiry. State survives across the 3–4
 * page navigations of the onboarding wizard but is intentionally transient — if the server restarts
 * mid-onboarding, the user simply starts the wizard again.
 */
@jakarta.inject.Singleton
public class OnboardingStateHolder {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingStateHolder.class);
    private static final long TTL_SECONDS = 600; // 10 minutes

    /** Mutable state for a single onboarding session. */
    public static final class OnboardingState {

        private final String tenantId;
        private final Instant createdAt;

        // Step 1
        private String workspaceName;
        private String orgTenantId;

        // Selected plan (from pricing page → signup → onboarding)
        private String plan;

        // Step 2
        private IndustryPreset industryPreset;

        // Step 3
        private Set<String> selectedJurisdictions = new LinkedHashSet<>();
        private Set<String> selectedEntities = new LinkedHashSet<>();

        public OnboardingState(String tenantId) {
            this.tenantId = tenantId;
            this.createdAt = Instant.now();
        }

        // -- Accessors --------------------------------------------------------

        public String tenantId() {
            return tenantId;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public String workspaceName() {
            return workspaceName;
        }

        public void setWorkspaceName(String workspaceName) {
            this.workspaceName = workspaceName;
        }

        /**
         * The tenant ID of the organization created in step 1, or {@code null} if the user chose a
         * personal workspace.
         */
        public String orgTenantId() {
            return orgTenantId;
        }

        public void setOrgTenantId(String orgTenantId) {
            this.orgTenantId = orgTenantId;
        }

        /** Returns the selected plan ID, or {@code "free"} if none was chosen. */
        public String plan() {
            return plan != null && !plan.isBlank() ? plan : "free";
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }

        /**
         * Returns the tenant ID that governance data should be seeded into. If an org was created,
         * returns the org tenant; otherwise returns the personal tenant.
         */
        public String activeTenantId() {
            return orgTenantId != null ? orgTenantId : tenantId;
        }

        public IndustryPreset industryPreset() {
            return industryPreset;
        }

        public void setIndustryPreset(IndustryPreset industryPreset) {
            this.industryPreset = industryPreset;
        }

        public Set<String> selectedJurisdictions() {
            return Collections.unmodifiableSet(selectedJurisdictions);
        }

        public void setSelectedJurisdictions(Collection<String> jurisdictions) {
            this.selectedJurisdictions = new LinkedHashSet<>(jurisdictions);
        }

        public Set<String> selectedEntities() {
            return Collections.unmodifiableSet(selectedEntities);
        }

        public void setSelectedEntities(Collection<String> entities) {
            this.selectedEntities = new LinkedHashSet<>(entities);
        }
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private final ConcurrentHashMap<String, OnboardingState> states = new ConcurrentHashMap<>();

    /** Creates or replaces onboarding state for the given personal tenant ID. */
    public OnboardingState start(String tenantId) {
        var state = new OnboardingState(tenantId);
        states.put(tenantId, state);
        LOG.info("Onboarding started for tenant: {}", tenantId);
        return state;
    }

    /** Retrieves the onboarding state, or {@code null} if expired / not started. */
    public OnboardingState get(String tenantId) {
        cleanupExpired();
        return states.get(tenantId);
    }

    /** Removes onboarding state (called after seeding completes). */
    public void complete(String tenantId) {
        states.remove(tenantId);
        LOG.info("Onboarding completed for tenant: {}", tenantId);
    }

    private void cleanupExpired() {
        Instant cutoff = Instant.now().minusSeconds(TTL_SECONDS);
        states.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }
}
