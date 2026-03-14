package com.koostack.cveil.core.models;

import java.util.List;
import java.util.UUID;

public record FieldView(
        UUID fieldId,
        String fieldName,
        String description,
        EntityView entity,
        List<CategoryView> categories) {
    public record EntityView(UUID entityId, String entityName) {}

    public record CategoryView(UUID categoryId, String categoryName) {}
}
