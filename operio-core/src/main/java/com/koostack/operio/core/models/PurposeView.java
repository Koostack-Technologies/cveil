package com.koostack.operio.core.models;

import java.util.List;

public record PurposeView(
        long purposeId,
        String purposeName,
        String description,
        List<RegulationLinkView> regulations,
        List<PolicyView> policies) {
    public record RegulationLinkView(
            String regulationName, String lawfulBasis, String onGranted, String onDenied) {}

    public record PolicyView(
            long policyId, String policyType, CategoryRef category, FieldRef field) {}

    public record CategoryRef(long categoryId, String categoryName) {}

    public record FieldRef(long fieldId, String fieldName, String entityName) {}
}
