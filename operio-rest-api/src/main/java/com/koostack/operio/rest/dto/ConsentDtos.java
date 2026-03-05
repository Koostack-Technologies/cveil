package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.BatchConsentEntry;
import com.koostack.operio.core.models.BatchConsentResult;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.SubjectConsentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for the consent endpoints
 * ({@code /api/v1/subjects}).
 *
 * <p>All types are Java records — immutable, serializable by Jackson out of
 * the box, and cheap to allocate. Each DTO provides static mapping methods
 * ({@code fromModel} / {@code toModel}) to convert between the REST
 * representation and the framework-agnostic {@code operio-core} models.
 *
 * <h2>Naming conventions</h2>
 * <ul>
 *   <li>{@code *Request} — inbound JSON body from the client</li>
 *   <li>{@code *Response} — outbound JSON body to the client</li>
 *   <li>{@code *Dto} — shared sub-structures used by both requests and
 *       responses</li>
 * </ul>
 */
public final class ConsentDtos {

    private ConsentDtos() {
        // Utility class — no instances
    }

    // ===================================================================
    // Grant / Revoke — single
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/subjects/consent/grant}.
     *
     * @param subject   the data subject identifier (e.g. user ID)
     * @param scope     the consent scope to grant
     * @param expiresAt optional expiration timestamp, or {@code null} for
     *                  no expiration
     */
    public record GrantRequest(
        @NotBlank(message = "subject must not be blank") @Size(
            max = 512,
            message = "subject must not exceed 512 characters"
        ) String subject,
        @NotNull(
            message = "scope must not be null"
        ) @Valid ConsentScopeDto scope,
        Instant expiresAt
    ) {}

    /**
     * Request body for {@code POST /api/v1/subjects/consent/revoke}.
     *
     * @param subject the data subject identifier
     * @param scope   the consent scope to revoke
     */
    public record RevokeRequest(
        @NotBlank(message = "subject must not be blank") @Size(
            max = 512,
            message = "subject must not exceed 512 characters"
        ) String subject,
        @NotNull(
            message = "scope must not be null"
        ) @Valid ConsentScopeDto scope
    ) {}

    // ===================================================================
    // Grant / Revoke — batch
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/subjects/consent/grant-all}.
     *
     * @param subject the data subject identifier
     * @param grants  the list of scopes (with optional expiration) to grant
     */
    public record BatchGrantRequest(
        @NotBlank(message = "subject must not be blank") @Size(
            max = 512,
            message = "subject must not exceed 512 characters"
        ) String subject,
        @NotNull(message = "grants must not be null") @NotEmpty(
            message = "grants must not be empty"
        ) @Size(
            max = 1000,
            message = "grants must not exceed 1000 entries"
        ) @Valid List<BatchConsentEntryDto> grants
    ) {}

    /**
     * Request body for {@code POST /api/v1/subjects/consent/revoke-all}.
     *
     * @param subject the data subject identifier
     * @param revokes the list of scopes (with optional expiration) to revoke
     */
    public record BatchRevokeRequest(
        @NotBlank(message = "subject must not be blank") @Size(
            max = 512,
            message = "subject must not exceed 512 characters"
        ) String subject,
        @NotNull(message = "revokes must not be null") @NotEmpty(
            message = "revokes must not be empty"
        ) @Size(
            max = 1000,
            message = "revokes must not exceed 1000 entries"
        ) @Valid List<BatchConsentEntryDto> revokes
    ) {}

    /**
     * A single entry within a batch grant or revoke request.
     *
     * @param scope     the consent scope
     * @param expiresAt optional expiration timestamp, or {@code null}
     */
    public record BatchConsentEntryDto(
        @NotNull(message = "scope must not be null")
        @Valid
        ConsentScopeDto scope,

        Instant expiresAt
    ) {
        /**
         * Converts this DTO to the core {@link BatchConsentEntry} model.
         *
         * @return the core batch consent entry
         */
        public BatchConsentEntry toModel() {
            return new BatchConsentEntry(scope.toModel(), expiresAt);
        }
    }

    /**
     * Response body for batch grant and batch revoke operations.
     *
     * @param succeeded the number of items that completed successfully
     * @param failed    the number of items that failed
     * @param errors    details for each failed item, possibly empty
     */
    public record BatchConsentResponse(
        int succeeded,
        int failed,
        List<BatchItemErrorDto> errors
    ) {
        /**
         * Creates a response DTO from the core {@link BatchConsentResult}.
         *
         * @param result the core batch result
         * @return the DTO representation
         */
        public static BatchConsentResponse fromModel(
            BatchConsentResult result
        ) {
            List<BatchItemErrorDto> errorDtos = result
                .errors()
                .stream()
                .map(e ->
                    new BatchItemErrorDto(
                        e.index(),
                        ConsentScopeDto.fromModel(e.scope()),
                        e.message()
                    )
                )
                .toList();
            return new BatchConsentResponse(
                result.succeeded(),
                result.failed(),
                errorDtos
            );
        }
    }

    /**
     * Describes a single failed item within a batch consent operation.
     *
     * @param index   the zero-based index of the failed item in the
     *                original request list
     * @param scope   the consent scope that failed
     * @param message a human-readable error message
     */
    public record BatchItemErrorDto(
        int index,
        ConsentScopeDto scope,
        String message
    ) {}

    // ===================================================================
    // Evaluate
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/subjects/{subjectId}/evaluate}.
     *
     * @param scopes  the set of consent scopes to evaluate
     */
    public record EvaluateRequest(
        @NotNull(message = "scopes must not be null") @NotEmpty(
            message = "scopes must not be empty"
        ) @Size(
            max = 100,
            message = "scopes must not exceed 100 entries"
        ) @Valid Set<ConsentScopeDto> scopes
    ) {}

    /**
     * Response body for consent evaluation.
     *
     * <p>Contains the full evaluation result: which scopes were granted,
     * which were denied, human-readable denial reasons, and structured
     * decision details for each scope.
     *
     * @param subject  the evaluated data subject
     * @param granted  the set of scopes that were granted
     * @param denied   the set of scopes that were denied
     * @param reasons  human-readable denial reasons keyed by field name
     * @param details  per-scope structured decision details
     */
    public record EvaluateResponse(
        String subject,
        Set<ConsentScopeDto> granted,
        Set<ConsentScopeDto> denied,
        Map<String, String> reasons,
        Map<String, DecisionDetailDto> details
    ) {
        /**
         * Creates a response DTO from the core {@link ConsentResponse}.
         *
         * @param response the core consent evaluation response
         * @return the DTO representation
         */
        public static EvaluateResponse fromModel(ConsentResponse response) {
            Set<ConsentScopeDto> grantedDtos = response
                .granted()
                .stream()
                .map(ConsentScopeDto::fromModel)
                .collect(Collectors.toSet());

            Set<ConsentScopeDto> deniedDtos = response
                .denied()
                .stream()
                .map(ConsentScopeDto::fromModel)
                .collect(Collectors.toSet());

            // Convert details map: core uses ConsentScope as key, DTO uses
            // a string key (fieldName or scope toString) for JSON friendliness.
            Map<String, DecisionDetailDto> detailDtos = response
                .details()
                .entrySet()
                .stream()
                .collect(
                    Collectors.toMap(
                        e -> scopeKey(e.getKey()),
                        e -> DecisionDetailDto.fromModel(e.getValue())
                    )
                );

            return new EvaluateResponse(
                response.subject(),
                grantedDtos,
                deniedDtos,
                response.reasons(),
                detailDtos
            );
        }

        /**
         * Produces a stable string key for a consent scope suitable for use
         * as a JSON object key in the details map.
         *
         * <p>Format: {@code "category:fieldName:entityName:purposeName"},
         * where null components are rendered as empty strings.
         */
        private static String scopeKey(ConsentScope scope) {
            return String.join(
                ":",
                nullSafe(scope.category()),
                nullSafe(scope.fieldName()),
                nullSafe(scope.entityName()),
                nullSafe(scope.purposeName())
            );
        }

        private static String nullSafe(String value) {
            return value != null ? value : "";
        }
    }

    /**
     * Structured detail of how a single consent decision was resolved.
     *
     * @param effectiveStatus the resolved status ({@code "GRANTED"},
     *                        {@code "REVOKED"}, or {@code null} if no
     *                        consent record exists)
     * @param resolvedBy      which level determined the outcome:
     *                        {@code "FIELD"}, {@code "CATEGORY"}, or
     *                        {@code "NONE"}
     * @param expiresAt       when the governing consent entry expires, or
     *                        {@code null} if no expiration
     */
    public record DecisionDetailDto(
        String effectiveStatus,
        String resolvedBy,
        Instant expiresAt
    ) {
        /**
         * Creates a DTO from the core {@link DecisionDetail}.
         *
         * @param detail the core decision detail
         * @return the DTO representation
         */
        public static DecisionDetailDto fromModel(DecisionDetail detail) {
            return new DecisionDetailDto(
                detail.effectiveStatus(),
                detail.resolvedBy(),
                detail.expiresAt()
            );
        }
    }

    // ===================================================================
    // DSAR (Data Subject Access Request)
    // ===================================================================

    /**
     * Response body for {@code GET /api/v1/subjects/{id}/export}.
     *
     * <p>Contains a complete export of all consent-related data held for
     * a subject (GDPR Article 20 — data portability).
     *
     * @param subject         the data subject identifier
     * @param consent         the subject's current consent ledger state
     * @param cachedKeyCount  the number of cached consent keys in Redis (informational)
     * @param exportedAt      when the export was generated
     */
    @Schema(
        description = "Complete export of all consent data for a subject (GDPR Article 20)"
    )
    public record SubjectDataExport(
        @Schema(
            description = "The data subject identifier",
            example = "user_123"
        ) String subject,
        @Schema(
            description = "The subject's current consent ledger state"
        ) SubjectConsentView consent,
        @Schema(
            description = "Number of cached consent keys in Redis (informational)",
            example = "5"
        ) long cachedKeyCount,
        @Schema(
            description = "Timestamp when the export was generated",
            example = "2026-02-23T10:30:00Z"
        ) Instant exportedAt
    ) {}

    /**
     * Response body for {@code DELETE /api/v1/subjects/{id}}.
     *
     * <p>Confirms the completion of a data subject access request (DSAR)
     * deletion operation. Contains counts of deleted records and a unique
     * request ID for forensic traceability.
     *
     * @param subject                the data subject identifier
     * @param deletionRequestId      unique request ID for this erasure operation
     * @param ledgerEntriesRevoked   number of consent ledger entries revoked
     * @param cachedKeysDeleted      number of cached keys deleted from Redis
     * @param status                 {@code "SUCCESS"} or {@code "PARTIAL_FAILURE"}
     * @param completedAt            when the deletion was completed
     */
    @Schema(
        description = "Confirmation receipt for a data subject erasure request"
    )
    public record DsarDeletionReceipt(
        @Schema(
            description = "The data subject identifier",
            example = "user_123"
        ) String subject,
        @Schema(
            description = "Unique request ID for this erasure operation",
            example = "550e8400-e29b-41d4-a716-446655440000"
        ) String deletionRequestId,
        @Schema(
            description = "Number of consent ledger entries revoked",
            example = "12"
        ) long ledgerEntriesRevoked,
        @Schema(
            description = "Number of cached keys deleted from Redis",
            example = "8"
        ) long cachedKeysDeleted,
        @Schema(
            description = "Status of the deletion operation",
            enumeration = { "PENDING_SHRED", "PARTIAL_FAILURE" },
            example = "PENDING_SHRED"
        ) String status,
        @Schema(
            description = "Timestamp when the deletion was completed",
            example = "2026-02-23T10:30:00Z"
        ) Instant completedAt
    ) {}
}
