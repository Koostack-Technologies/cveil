package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.ApiKeyInfo;
import com.koostack.operio.core.models.TenantInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/**
 * Request and response DTOs for the platform admin endpoints
 * ({@code /api/v1/platform}).
 *
 * <p>These DTOs cover the super-admin control plane: tenant lifecycle
 * (create, delete, list, get) and API key management (create, revoke,
 * list). All platform endpoints require the {@code super-admin} role.
 *
 * <p>All types are Java records — immutable, serializable by Jackson out
 * of the box, and cheap to allocate. Each response DTO provides a static
 * {@code fromModel} mapping method to convert from the framework-agnostic
 * {@code operio-core} model.
 *
 * <h2>Naming conventions</h2>
 * <ul>
 *   <li>{@code Create*Request} — inbound JSON body for creation</li>
 *   <li>{@code *Response} — outbound JSON body for query results</li>
 *   <li>{@code *CreatedResponse} — outbound JSON body returned immediately
 *       after creation (may contain one-time-use secrets like raw API keys)</li>
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
     * <ol>
     *   <li>A row in the platform {@code tenant} table</li>
     *   <li>A dedicated Postgres schema with Flyway-applied consent
     *       migrations</li>
     *   <li>A tenant-scoped Redis ACL user restricted to the tenant's
     *       key prefix</li>
     * </ol>
     *
     * @param tenantId    unique tenant identifier (e.g. {@code "acme_corp"})
     *                    — must be a valid identifier (alphanumeric +
     *                    underscores, no spaces)
     * @param displayName human-readable tenant name (e.g. {@code "Acme Corp"})
     * @param plan        the subscription plan ({@code "free"},
     *                    {@code "pro"}, {@code "enterprise"})
     */
    public record CreateTenantRequest(
        @NotBlank(message = "tenantId must not be blank") @Size(
            max = 63,
            message = "tenantId must not exceed 63 characters"
        ) @Pattern(
            regexp = "^[a-zA-Z][a-zA-Z0-9_]*$",
            message = "tenantId must start with a letter and contain only alphanumeric characters and underscores"
        ) String tenantId,
        @Size(
            max = 255,
            message = "displayName must not exceed 255 characters"
        ) String displayName,
        @NotBlank(message = "plan must not be blank") @Size(
            max = 50,
            message = "plan must not exceed 50 characters"
        ) String plan
    ) {}

    /**
     * Response body for tenant queries ({@code GET /api/v1/platform/tenants}
     * and {@code GET /api/v1/platform/tenants/{id}}).
     *
     * <p>Contains the full tenant metadata including plan-level limits used
     * for rate limiting and quota enforcement.
     *
     * @param tenantId          unique tenant identifier
     * @param displayName       human-readable tenant name
     * @param plan              subscription plan
     * @param status            tenant status ({@code "ACTIVE"},
     *                          {@code "SUSPENDED"}, {@code "DELETED"})
     * @param evalLimitMonthly  maximum consent evaluations per calendar month
     * @param subjectLimit      maximum distinct data subjects
     * @param burstLimitPerMin  maximum requests per minute (burst cap)
     * @param createdAt         when the tenant was provisioned
     */
    public record TenantResponse(
        String tenantId,
        String displayName,
        String plan,
        String status,
        long evalLimitMonthly,
        long subjectLimit,
        int burstLimitPerMin,
        Instant createdAt
    ) {
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
                info.evalLimitMonthly(),
                info.subjectLimit(),
                info.burstLimitPerMin(),
                info.createdAt()
            );
        }
    }

    // ===================================================================
    // API Key
    // ===================================================================

    /**
     * Request body for {@code PUT /api/v1/platform/tenants/{id}/plan}.
     *
     * <p>Updates a tenant's subscription plan and recalculates all
     * plan-level rate limits (evaluation quota, subject limit, burst cap).
     *
     * @param plan the new subscription plan ({@code "free"},
     *             {@code "pro"}, {@code "enterprise"})
     */
    public record UpdatePlanRequest(
        @NotBlank(message = "plan must not be blank") @Size(
            max = 50,
            message = "plan must not exceed 50 characters"
        ) String plan
    ) {}

    // ===================================================================
    // API Key (creation / listing)
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/platform/api-keys}.
     *
     * <p>Creates an API key for a tenant. The raw key is returned only
     * once in the {@link ApiKeyCreatedResponse} — it is never stored
     * in plaintext and cannot be retrieved later.
     *
     * @param tenantId the owning tenant identifier
     * @param subject  the subject (user or service account) this key
     *                 represents (e.g. {@code "admin@acme.com"},
     *                 {@code "consent-service"})
     * @param roles    the roles/authorities granted to this key
     *                 (e.g. {@code ["admin", "consent-writer"]})
     */
    public record CreateApiKeyRequest(
        @NotBlank(message = "tenantId must not be blank") @Size(
            max = 63,
            message = "tenantId must not exceed 63 characters"
        ) String tenantId,
        @NotBlank(message = "subject must not be blank") @Size(
            max = 255,
            message = "subject must not exceed 255 characters"
        ) String subject,
        @NotNull(message = "roles must not be null") Set<String> roles
    ) {}

    /**
     * Response body returned immediately after API key creation.
     *
     * <p><strong>Security note:</strong> The {@code rawKey} field contains
     * the full API key in plaintext. This is the <em>only</em> time the
     * raw key is available — it is stored as a SHA-256 hash in the
     * database. Clients must securely store the raw key after receiving
     * this response.
     *
     * @param rawKey    the full API key in plaintext (one-time-use secret)
     * @param keyPrefix the first 8 characters of the raw key (used for
     *                  display and revocation)
     * @param tenantId  the owning tenant identifier
     * @param subject   the subject this key represents
     * @param roles     the roles/authorities granted to this key
     */
    public record ApiKeyCreatedResponse(
        String rawKey,
        String keyPrefix,
        String tenantId,
        String subject,
        Set<String> roles
    ) {}

    /**
     * Response body for API key listing queries
     * ({@code GET /api/v1/platform/api-keys?tenantId=...}).
     *
     * <p>Contains metadata only — never the raw key or its hash. The
     * {@code keyPrefix} can be used for display and revocation.
     *
     * @param keyPrefix  the first 8 characters of the raw key
     * @param tenantId   the owning tenant identifier
     * @param subject    the subject this key represents
     * @param roles      the roles/authorities granted to this key
     * @param status     key status ({@code "ACTIVE"}, {@code "REVOKED"})
     * @param createdAt  when the key was created
     */
    public record ApiKeyResponse(
        String keyPrefix,
        String tenantId,
        String subject,
        Set<String> roles,
        String status,
        Instant createdAt
    ) {
        /**
         * Creates a response DTO from the core {@link ApiKeyInfo} model.
         *
         * @param info the core API key info — must not be {@code null}
         * @return the DTO representation
         */
        public static ApiKeyResponse fromModel(ApiKeyInfo info) {
            return new ApiKeyResponse(
                info.keyPrefix(),
                info.tenantId(),
                info.subject(),
                info.roles(),
                info.status(),
                info.createdAt()
            );
        }
    }
}
