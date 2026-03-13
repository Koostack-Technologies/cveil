package com.koostack.cveil.rest.dto;

import com.koostack.cveil.core.models.TenantInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Request and response DTOs for the platform admin endpoints ({@code /api/v1/platform}).
 *
 * <p>These DTOs cover the super-admin control plane: tenant lifecycle (create, delete, list, get)
 * and API key management (create, revoke, list). All platform endpoints require the {@code
 * super-admin} role.
 *
 * <p>All types are Java records — immutable, serializable by Jackson out of the box, and cheap to
 * allocate. Each response DTO provides a static {@code fromModel} mapping method to convert from
 * the framework-agnostic {@code cveil-core} model.
 *
 * <h2>Naming conventions</h2>
 *
 * <ul>
 *   <li>{@code Create*Request} — inbound JSON body for creation
 *   <li>{@code *Response} — outbound JSON body for query results
 *   <li>{@code *CreatedResponse} — outbound JSON body returned immediately after creation (may
 *       contain one-time-use secrets like raw API keys)
 * </ul>
 */
public final class PlatformDtos {

    private PlatformDtos() {
        // Utility class — no instances
    }

    // ===================================================================
    // Tenant
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/platform/tenants}.
     *
     * <p>Creates a new tenant on the platform. This triggers:
     *
     * <ol>
     *   <li>A row in the platform {@code tenant} table
     *   <li>A dedicated Postgres schema with Flyway-applied consent migrations
     *   <li>A tenant-scoped Redis ACL user restricted to the tenant's key prefix
     * </ol>
     *
     * @param tenantId unique tenant identifier (e.g. {@code "acme_corp"}) — must be a valid
     *     identifier (alphanumeric + underscores, no spaces)
     * @param displayName human-readable tenant name (e.g. {@code "Acme Corp"})
     * @param plan the subscription plan ({@code "free"}, {@code "pro"}, {@code "enterprise"})
     */
    public record CreateTenantRequest(
            @NotBlank(message = "tenantId must not be blank") @Size(max = 63, message = "tenantId must not exceed 63 characters") @Pattern(
                            regexp = "^[a-zA-Z][a-zA-Z0-9_]*$",
                            message =
                                    "tenantId must start with a letter and contain only"
                                            + " alphanumeric characters and underscores")
                    String tenantId,
            @Size(max = 255, message = "displayName must not exceed 255 characters") String displayName,
            @NotBlank(message = "plan must not be blank") @Size(max = 50, message = "plan must not exceed 50 characters") String plan,
            @jakarta.validation.constraints.Email(
                            message = "billingEmail must be a valid email address")
                    String billingEmail) {}

    /**
     * Response body for tenant queries ({@code GET /api/v1/platform/tenants} and {@code GET
     * /api/v1/platform/tenants/{id}}).
     *
     * <p>Contains the tenant metadata. Rate-limit and quota fields are now managed at the
     * subscription level via {@code BillingQueryService}.
     *
     * @param tenantId unique tenant identifier
     * @param displayName human-readable tenant name
     * @param plan subscription plan
     * @param status tenant status ({@code "ACTIVE"}, {@code "SUSPENDED"}, {@code "DELETED"})
     * @param createdAt when the tenant was provisioned
     */
    public record TenantResponse(
            String tenantId, String displayName, String plan, String status, Instant createdAt) {
        /**
         * Creates a response DTO from the core {@link TenantInfo} model.
         *
         * @param info the core tenant info — must not be {@code null}
         * @return the DTO representation
         */
        public static TenantResponse fromModel(TenantInfo info) {
            return new TenantResponse(
                    info.tenantId(),
                    info.displayName(),
                    info.plan(),
                    info.status(),
                    info.createdAt());
        }

        /**
         * Creates a response DTO from the CQRS {@link
         * com.koostack.cveil.core.platform.core.models.TenantInfo} model.
         *
         * @param info the CQRS tenant info — must not be {@code null}
         * @return the DTO representation
         */
        public static TenantResponse fromCoreModel(
                com.koostack.cveil.core.platform.core.models.TenantInfo info) {
            return new TenantResponse(
                    info.tenantId(),
                    info.displayName(),
                    info.plan(),
                    info.status(),
                    info.createdAt());
        }
    }

    // ===================================================================
    // API Key
    // ===================================================================

    /**
     * Request body for {@code PUT /api/v1/platform/tenants/{id}/plan}.
     *
     * <p>Updates a tenant's subscription plan and recalculates all plan-level rate limits
     * (evaluation quota, subject limit, burst cap).
     *
     * @param plan the new subscription plan ({@code "free"}, {@code "pro"}, {@code "enterprise"})
     */
    public record UpdatePlanRequest(
            @NotBlank(message = "plan must not be blank") @Size(max = 50, message = "plan must not exceed 50 characters") String plan) {}
}
