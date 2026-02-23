package com.koostack.operio.rest.dto;

import com.koostack.operio.core.Models.CategoryInfo;
import com.koostack.operio.core.Models.EntityInfo;
import com.koostack.operio.core.Models.FieldInfo;
import com.koostack.operio.core.Models.PurposeInfo;
import com.koostack.operio.core.Models.RegulationInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response DTOs for the admin schema endpoints
 * ({@code /api/v1/admin}).
 *
 * <p>These DTOs cover the consent schema management surface: registering
 * and listing fields, categories, entities, purposes, and regulations,
 * plus linking fields to categories and purposes to regulations.
 *
 * <p>All types are Java records — immutable, serializable by Jackson out
 * of the box, and cheap to allocate. Each response DTO provides a static
 * {@code fromModel} mapping method to convert from the framework-agnostic
 * {@code operio-core} model.
 *
 * <h2>Naming conventions</h2>
 * <ul>
 *   <li>{@code Register*Request} — inbound JSON body for creation / upsert</li>
 *   <li>{@code Link*Request} — inbound JSON body for relationship creation</li>
 *   <li>{@code *Response} — outbound JSON body for query results</li>
 * </ul>
 */
public final class AdminDtos {

    private AdminDtos() {
        // Utility class — no instances
    }

    // ===================================================================
    // Field
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/fields}.
     *
     * <p>Registers a sensitive field in the consent schema. Uses upsert
     * semantics — if a field with the same name and entity already exists,
     * its description is updated.
     *
     * @param fieldName   the field name (e.g. {@code "email"},
     *                    {@code "home.street"})
     * @param entityName  the owning entity (e.g. {@code "User"}), or
     *                    {@code null} for entity-less fields
     * @param description optional human-readable description, or {@code null}
     */
    public record RegisterFieldRequest(
        @NotBlank(message = "fieldName must not be blank") @Size(
            max = 255,
            message = "fieldName must not exceed 255 characters"
        ) String fieldName,
        @Size(
            max = 255,
            message = "entityName must not exceed 255 characters"
        ) String entityName,
        @Size(
            max = 1000,
            message = "description must not exceed 1000 characters"
        ) String description
    ) {}

    /**
     * Response body for field queries ({@code GET /api/v1/admin/fields}).
     *
     * @param fieldName   the field name
     * @param entityName  the owning entity, or {@code null}
     * @param description the field description, or {@code null}
     */
    public record FieldResponse(
        String fieldName,
        String entityName,
        String description
    ) {
        /**
         * Creates a response DTO from the core {@link FieldInfo} model.
         *
         * @param info the core field info — must not be {@code null}
         * @return the DTO representation
         */
        public static FieldResponse fromModel(FieldInfo info) {
            return new FieldResponse(
                info.fieldName(),
                info.entityName(),
                info.description()
            );
        }
    }

    // ===================================================================
    // Category
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/categories}.
     *
     * <p>Registers a consent category. Uses upsert semantics — if a
     * category with the same name already exists, its description is
     * updated.
     *
     * @param categoryName the category name (e.g. {@code "PII"},
     *                     {@code "HEALTH"})
     * @param description  optional human-readable description, or {@code null}
     */
    public record RegisterCategoryRequest(
        @NotBlank(message = "categoryName must not be blank") @Size(
            max = 255,
            message = "categoryName must not exceed 255 characters"
        ) String categoryName,
        @Size(
            max = 1000,
            message = "description must not exceed 1000 characters"
        ) String description
    ) {}

    /**
     * Response body for category queries
     * ({@code GET /api/v1/admin/categories}).
     *
     * @param categoryName the category name
     * @param description  the category description, or {@code null}
     */
    public record CategoryResponse(String categoryName, String description) {
        /**
         * Creates a response DTO from the core {@link CategoryInfo} model.
         *
         * @param info the core category info — must not be {@code null}
         * @return the DTO representation
         */
        public static CategoryResponse fromModel(CategoryInfo info) {
            return new CategoryResponse(
                info.categoryName(),
                info.description()
            );
        }
    }

    // ===================================================================
    // Entity
    // ===================================================================

    /**
     * Response body for entity queries
     * ({@code GET /api/v1/admin/entities}).
     *
     * <p>Entities are not registered directly — they are implicitly
     * created when fields with entity names are registered. This DTO
     * is read-only.
     *
     * @param entityName  the entity name (e.g. {@code "User"},
     *                    {@code "Patient"})
     * @param description the entity description, or {@code null}
     */
    public record EntityResponse(String entityName, String description) {
        /**
         * Creates a response DTO from the core {@link EntityInfo} model.
         *
         * @param info the core entity info — must not be {@code null}
         * @return the DTO representation
         */
        public static EntityResponse fromModel(EntityInfo info) {
            return new EntityResponse(info.entityName(), info.description());
        }
    }

    // ===================================================================
    // Purpose
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/purposes}.
     *
     * <p>Registers a consent purpose. Uses upsert semantics — if a
     * purpose with the same name already exists, its description is
     * updated.
     *
     * @param purposeName the purpose identifier (e.g. {@code "marketing"},
     *                    {@code "analytics"})
     * @param description optional human-readable description, or {@code null}
     */
    public record RegisterPurposeRequest(
        @NotBlank(message = "purposeName must not be blank") @Size(
            max = 255,
            message = "purposeName must not exceed 255 characters"
        ) String purposeName,
        @Size(
            max = 1000,
            message = "description must not exceed 1000 characters"
        ) String description
    ) {}

    /**
     * Response body for purpose queries
     * ({@code GET /api/v1/admin/purposes}).
     *
     * @param purposeName the purpose name
     * @param description the purpose description, or {@code null}
     */
    public record PurposeResponse(String purposeName, String description) {
        /**
         * Creates a response DTO from the core {@link PurposeInfo} model.
         *
         * @param info the core purpose info — must not be {@code null}
         * @return the DTO representation
         */
        public static PurposeResponse fromModel(PurposeInfo info) {
            return new PurposeResponse(info.purposeName(), info.description());
        }
    }

    // ===================================================================
    // Regulation
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/regulations}.
     *
     * <p>Registers a regulation. Uses upsert semantics — if a regulation
     * with the same name already exists, its fields are updated.
     *
     * @param regulationName short identifier (e.g. {@code "GDPR"},
     *                       {@code "CCPA"})
     * @param fullName       full legal name (e.g. {@code "General Data
     *                       Protection Regulation"})
     * @param jurisdiction   geographic jurisdiction (e.g. {@code "EU"},
     *                       {@code "US-CA"})
     * @param description    optional human-readable description, or
     *                       {@code null}
     * @param url            optional URL to the regulation text, or
     *                       {@code null}
     */
    public record RegisterRegulationRequest(
        @NotBlank(message = "regulationName must not be blank") @Size(
            max = 255,
            message = "regulationName must not exceed 255 characters"
        ) String regulationName,
        @NotBlank(message = "fullName must not be blank") @Size(
            max = 500,
            message = "fullName must not exceed 500 characters"
        ) String fullName,
        @NotBlank(message = "jurisdiction must not be blank") @Size(
            max = 100,
            message = "jurisdiction must not exceed 100 characters"
        ) String jurisdiction,
        @Size(
            max = 1000,
            message = "description must not exceed 1000 characters"
        ) String description,
        @Size(
            max = 2000,
            message = "url must not exceed 2000 characters"
        ) String url
    ) {}

    /**
     * Response body for regulation queries
     * ({@code GET /api/v1/admin/regulations}).
     *
     * @param regulationName short identifier
     * @param fullName       full legal name
     * @param jurisdiction   geographic jurisdiction
     * @param description    the regulation description, or {@code null}
     * @param url            URL to the regulation text, or {@code null}
     */
    public record RegulationResponse(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {
        /**
         * Creates a response DTO from the core {@link RegulationInfo} model.
         *
         * @param info the core regulation info — must not be {@code null}
         * @return the DTO representation
         */
        public static RegulationResponse fromModel(RegulationInfo info) {
            return new RegulationResponse(
                info.regulationName(),
                info.fullName(),
                info.jurisdiction(),
                info.description(),
                info.url()
            );
        }
    }

    // ===================================================================
    // Link — Field ↔ Category
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/fields/links}.
     *
     * <p>Links a previously registered field to a previously registered
     * category. Idempotent — if the link already exists, this is a no-op.
     *
     * @param fieldName    the field name to link
     * @param entityName   the owning entity name, or {@code null} for
     *                     entity-less fields
     * @param categoryName the category name to link to
     */
    public record LinkFieldCategoryRequest(
        @NotBlank(message = "fieldName must not be blank") @Size(
            max = 255,
            message = "fieldName must not exceed 255 characters"
        ) String fieldName,
        @Size(
            max = 255,
            message = "entityName must not exceed 255 characters"
        ) String entityName,
        @NotBlank(message = "categoryName must not be blank") @Size(
            max = 255,
            message = "categoryName must not exceed 255 characters"
        ) String categoryName
    ) {}

    // ===================================================================
    // Link — Purpose ↔ Regulation
    // ===================================================================

    /**
     * Request body for {@code POST /api/v1/admin/purposes/links}.
     *
     * <p>Links a previously registered purpose to a previously registered
     * regulation with a legal basis. Idempotent — if the link already
     * exists, this is a no-op.
     *
     * @param purposeName    the purpose to link
     * @param regulationName the regulation to link to
     * @param legalBasis     the legal basis for processing under this
     *                       regulation (e.g. {@code "consent"},
     *                       {@code "legitimate_interest"},
     *                       {@code "contract"})
     */
    public record LinkPurposeRegulationRequest(
        @NotBlank(message = "purposeName must not be blank") @Size(
            max = 255,
            message = "purposeName must not exceed 255 characters"
        ) String purposeName,
        @NotBlank(message = "regulationName must not be blank") @Size(
            max = 255,
            message = "regulationName must not exceed 255 characters"
        ) String regulationName,
        @NotBlank(message = "legalBasis must not be blank") @Size(
            max = 255,
            message = "legalBasis must not exceed 255 characters"
        ) String legalBasis
    ) {}
}
