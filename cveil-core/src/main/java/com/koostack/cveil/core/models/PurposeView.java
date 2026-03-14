package com.koostack.cveil.core.models;

import java.util.List;
import java.util.UUID;

public record PurposeView(
        UUID purposeId,
        String purposeName,
        String description,
        List<RegulationLinkView> regulations,
        List<PolicyView> policies) {
    public record RegulationLinkView(
            String regulationName, String lawfulBasis, String onGranted, String onDenied) {}

    public record PolicyView(
            UUID policyId, String policyType, CategoryRef category, FieldRef field) {}

    public record CategoryRef(UUID categoryId, String categoryName) {}

    public record FieldRef(UUID fieldId, String fieldName, String entityName) {}
}
