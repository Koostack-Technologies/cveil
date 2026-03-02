package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response DTOs for the admin schema endpoints
 * ({@code /api/v1/admin}).
 *
 * <p>Response types are the aggregate view models from operio-core
 * (FieldView, PurposeView, RegulationView) — returned directly as JSON.
 * This class contains only request DTOs.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ===================================================================
    // Field
    // ===================================================================

    public record RegisterFieldRequest(
        @NotBlank(message = "fieldName must not be blank")
        @Size(max = 255) String fieldName,
        @Size(max = 255) String entityName,
        @Size(max = 1000) String description,
        @Size(max = 1000) String entityDescription
    ) {}

    // ===================================================================
    // Purpose
    // ===================================================================

    public record RegisterPurposeRequest(
        @NotBlank(message = "purposeName must not be blank")
        @Size(max = 255) String purposeName,
        @Size(max = 1000) String description
    ) {}

    public record AddPurposePolicyRequest(
        @NotBlank(message = "purposeName must not be blank")
        @Size(max = 255) String purposeName,
        @NotBlank(message = "policyType must not be blank")
        @Size(max = 20) String policyType,
        @Size(max = 255) String fieldName,
        @Size(max = 255) String entityName,
        @Size(max = 255) String categoryName
    ) {}

    public record RemovePurposePolicyRequest(
        @NotBlank(message = "purposeName must not be blank")
        @Size(max = 255) String purposeName,
        @NotBlank(message = "policyType must not be blank")
        @Size(max = 20) String policyType,
        @Size(max = 255) String fieldName,
        @Size(max = 255) String entityName,
        @Size(max = 255) String categoryName
    ) {}

    // ===================================================================
    // Regulation
    // ===================================================================

    public record RegisterRegulationRequest(
        @NotBlank(message = "regulationName must not be blank")
        @Size(max = 255) String regulationName,
        @Size(max = 1000) String description
    ) {}

    public record AddJurisdictionRequest(
        @NotBlank(message = "regulationName must not be blank")
        @Size(max = 255) String regulationName,
        @NotBlank(message = "jurisdictionName must not be blank")
        @Size(max = 255) String jurisdictionName,
        @Size(max = 1000) String description
    ) {}

    public record RemoveJurisdictionRequest(
        @NotBlank(message = "regulationName must not be blank")
        @Size(max = 255) String regulationName,
        @NotBlank(message = "jurisdictionName must not be blank")
        @Size(max = 255) String jurisdictionName
    ) {}

    // ===================================================================
    // Link — Field ↔ Category
    // ===================================================================

    public record AssignFieldCategoryRequest(
        @NotBlank(message = "fieldName must not be blank")
        @Size(max = 255) String fieldName,
        @Size(max = 255) String entityName,
        @NotBlank(message = "categoryName must not be blank")
        @Size(max = 255) String categoryName,
        @Size(max = 1000) String categoryDescription
    ) {}

    // ===================================================================
    // Link — Purpose ↔ Regulation
    // ===================================================================

    public record LinkPurposeRegulationRequest(
        @NotBlank(message = "purposeName must not be blank")
        @Size(max = 255) String purposeName,
        @NotBlank(message = "regulationName must not be blank")
        @Size(max = 255) String regulationName,
        @Size(max = 255) String lawfulBasis,
        @Size(max = 50) String onGranted,
        @Size(max = 50) String onDenied
    ) {}
}
