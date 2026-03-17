package com.koostack.cveil.rest.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the provisioning status of newly created tenants.
 *
 * <p>When a user signs up, the fast synchronous portion (Keycloak user, tenant, roles, Redis)
 * completes immediately. The slower deferred portion (governance seeding, billing) runs
 * asynchronously. This tracker lets the "Setting up workspace" loading page poll for completion.
 *
 * <p>Entries are stored in-memory and auto-expire after {@link #TTL_SECONDS} seconds. This is
 * intentional — provisioning is a one-time transient event and does not need persistence.
 */
@ApplicationScoped
public class ProvisioningTracker {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisioningTracker.class);

    /** Entries older than this are considered expired and will be cleaned up on access. */
    private static final long TTL_SECONDS = 600; // 10 minutes

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    public record ProvisioningStatus(
            Status status,
            String currentStep,
            int completedSteps,
            int totalSteps,
            String errorMessage,
            Instant createdAt) {

        static ProvisioningStatus inProgress(String step, int completed, int total) {
            return new ProvisioningStatus(
                    Status.IN_PROGRESS, step, completed, total, null, Instant.now());
        }

        static ProvisioningStatus completed() {
            return new ProvisioningStatus(Status.COMPLETED, null, 0, 0, null, Instant.now());
        }

        static ProvisioningStatus failed(String error) {
            return new ProvisioningStatus(Status.FAILED, null, 0, 0, error, Instant.now());
        }
    }

    private final ConcurrentHashMap<String, ProvisioningStatus> statuses =
            new ConcurrentHashMap<>();

    /**
     * Marks a tenant as provisioning in-progress.
     *
     * @param tenantId the tenant being provisioned
     * @param totalSteps the total number of deferred steps to execute
     */
    public void start(String tenantId, int totalSteps) {
        statuses.put(
                tenantId, ProvisioningStatus.inProgress("Initializing workspace", 0, totalSteps));
        LOG.info("Provisioning started for tenant: {} ({} steps)", tenantId, totalSteps);
    }

    /**
     * Updates the current step for a tenant's provisioning.
     *
     * @param tenantId the tenant being provisioned
     * @param stepName human-readable name of the current step
     * @param completedSteps number of steps completed so far
     * @param totalSteps total number of steps
     */
    public void updateStep(String tenantId, String stepName, int completedSteps, int totalSteps) {
        statuses.put(tenantId, ProvisioningStatus.inProgress(stepName, completedSteps, totalSteps));
        LOG.debug(
                "Provisioning step for tenant {}: {} ({}/{})",
                tenantId,
                stepName,
                completedSteps,
                totalSteps);
    }

    /** Marks provisioning as complete for the given tenant. */
    public void complete(String tenantId) {
        statuses.put(tenantId, ProvisioningStatus.completed());
        LOG.info("Provisioning completed for tenant: {}", tenantId);
    }

    /**
     * Marks provisioning as failed for the given tenant.
     *
     * @param tenantId the tenant whose provisioning failed
     * @param errorMessage a description of what went wrong
     */
    public void fail(String tenantId, String errorMessage) {
        statuses.put(tenantId, ProvisioningStatus.failed(errorMessage));
        LOG.error("Provisioning failed for tenant {}: {}", tenantId, errorMessage);
    }

    /**
     * Returns the provisioning status for a tenant, or {@code null} if no provisioning is tracked
     * (meaning the tenant was already fully provisioned before this tracker was introduced, or the
     * entry has expired).
     *
     * @param tenantId the tenant to check
     * @return the provisioning status, or {@code null}
     */
    public ProvisioningStatus getStatus(String tenantId) {
        cleanupExpired();
        return statuses.get(tenantId);
    }

    /** Removes entries older than {@link #TTL_SECONDS}. Called lazily on access. */
    private void cleanupExpired() {
        Instant cutoff = Instant.now().minusSeconds(TTL_SECONDS);
        statuses.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }
}
