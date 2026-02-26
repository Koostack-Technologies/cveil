package com.koostack.operio.core.data.consent.aggregates;

import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate representing a consent-schema entity with its nested fields.
 *
 * <p>Keyed by entity name. Entity-less fields use the sentinel name
 * {@code "__default__"}.
 */
public record EntityAggregate(
    String tenantId,
    UUID aggregateId,
    String entityName,
    String description,
    List<FieldEntry> fields,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-entity";
    public static final String DEFAULT_ENTITY = "__default__";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String entityName) {
        String key = entityName != null && !entityName.isBlank()
            ? entityName : DEFAULT_ENTITY;
        return UUID.nameUUIDFromBytes(
            ("consent-entity:" + key).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static String normalizeEntityName(String entityName) {
        return entityName != null && !entityName.isBlank()
            ? entityName : DEFAULT_ENTITY;
    }

    public static EntityAggregate create(String tenantId, String entityName) {
        String name = normalizeEntityName(entityName);
        return new EntityAggregate(
            tenantId, deriveId(name), name, null, List.of(), 0L
        );
    }

    /** Returns a new aggregate with the given field upserted. */
    public EntityAggregate withField(String fieldName, String fieldDescription) {
        var updated = new ArrayList<>(fields);
        updated.removeIf(f -> f.fieldName().equals(fieldName));
        updated.add(new FieldEntry(fieldName, fieldDescription, List.of()));
        return new EntityAggregate(
            tenantId, aggregateId, entityName, description,
            List.copyOf(updated), version
        );
    }

    /** Returns a new aggregate with a category link added to the named field. */
    public EntityAggregate withFieldCategoryLink(
        String fieldName, String categoryName
    ) {
        var updated = new ArrayList<>(fields);
        for (int i = 0; i < updated.size(); i++) {
            FieldEntry f = updated.get(i);
            if (f.fieldName().equals(fieldName)) {
                var cats = new ArrayList<>(f.categories());
                if (!cats.contains(categoryName)) {
                    cats.add(categoryName);
                }
                updated.set(
                    i,
                    new FieldEntry(f.fieldName(), f.description(),
                        List.copyOf(cats))
                );
                return new EntityAggregate(
                    tenantId, aggregateId, entityName, description,
                    List.copyOf(updated), version
                );
            }
        }
        throw new IllegalArgumentException(
            "No field '" + fieldName + "' found in entity '" + entityName + "'"
        );
    }

    public record FieldEntry(
        String fieldName,
        String description,
        List<String> categories
    ) {}
}
