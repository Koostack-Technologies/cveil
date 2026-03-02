package com.koostack.operio.core.models;

import java.util.List;

public record FieldView(
    long fieldId,
    String fieldName,
    String description,
    EntityView entity,
    List<CategoryView> categories
) {
    public record EntityView(long entityId, String entityName) {}
    public record CategoryView(long categoryId, String categoryName) {}
}
