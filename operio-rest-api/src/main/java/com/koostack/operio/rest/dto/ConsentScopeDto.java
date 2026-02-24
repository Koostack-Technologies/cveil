package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.ConsentScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * DTO representing a consent scope — the combination of category, field,
 * entity, and purpose that uniquely identifies what data processing is
 * being consented to.
 *
 * <p>Maps 1:1 to {@link ConsentScope} in the core model. The DTO uses
 * nullable strings for optional components (entity, purpose) — Jackson
 * will omit {@code null} fields if configured with
 * {@code NON_NULL} inclusion, or include them as JSON {@code null}.
 *
 * @param category   the consent category (e.g. {@code "PII"}, {@code "HEALTH"})
 * @param fieldName  the sensitive field name (e.g. {@code "email"})
 * @param entityName the owning entity (e.g. {@code "User"}), or {@code null}
 *                   for entity-less fields
 * @param purposeName the consent purpose (e.g. {@code "marketing"}), or
 *                    {@code null} if purpose is not specified
 */
@Schema(
    description = "Consent scope identifying the combination of category, field, " +
        "entity, and purpose that uniquely identifies a data processing consent"
)
public record ConsentScopeDto(
    @NotBlank(message = "category must not be blank")
    @Size(max = 255, message = "category must not exceed 255 characters")
    @Schema(
        description = "The consent category (e.g. PII, HEALTH)",
        example = "PII",
        required = true
    )
    String category,

    @NotBlank(message = "fieldName must not be blank")
    @Size(max = 255, message = "fieldName must not exceed 255 characters")
    @Schema(
        description = "The sensitive field name (e.g. email, ssn)",
        example = "email",
        required = true
    )
    String fieldName,

    @Size(max = 255, message = "entityName must not exceed 255 characters")
    @Schema(
        description = "The owning entity (e.g. User, Patient), or null for entity-less fields",
        example = "User",
        nullable = true
    )
    String entityName,

    @Size(max = 255, message = "purposeName must not exceed 255 characters")
    @Schema(
        description = "The consent purpose (e.g. marketing, analytics), or null if not specified",
        example = "marketing",
        nullable = true
    )
    String purposeName
) {
    // -------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------

    /**
     * Converts this DTO to the core {@link ConsentScope} model.
     *
     * @return the equivalent core consent scope
     */
    public ConsentScope toModel() {
        return new ConsentScope(category, fieldName, entityName, purposeName);
    }

    /**
     * Creates a DTO from the core {@link ConsentScope} model.
     *
     * @param scope the core consent scope — must not be {@code null}
     * @return the equivalent DTO
     */
    public static ConsentScopeDto fromModel(ConsentScope scope) {
        return new ConsentScopeDto(
            scope.category(),
            scope.fieldName(),
            scope.entityName(),
            scope.purposeName()
        );
    }
}
