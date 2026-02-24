package com.koostack.operio.plugin.masking.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.service.MaskingStrategy;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Jackson-based {@link MaskingStrategy} that converts the target object to a
 * mutable JSON tree, walks every node, and replaces denied fields with
 * type-safe sentinel values before converting back to the original Java type.
 *
 * <p><strong>Path-aware matching</strong></p>
 * Field names in the denied {@link ConsentScope} set are interpreted as
 * <strong>qualified dot-paths</strong> rooted at the top-level object being
 * masked. For example, given:
 * <pre>{@code
 *   class User {
 *       String email;          // path: "email"
 *       Address home;          // path prefix: "home"
 *   }
 *   class Address {
 *       String street;         // path: "home.street"
 *   }
 * }</pre>
 * A denied scope with {@code fieldName = "home.street"} masks only the
 * {@code street} inside the {@code home} object, while a scope with
 * {@code fieldName = "email"} masks only the root-level email.
 *
 * <p>Inside JSON arrays each element inherits the array field's path, so
 * {@code "addresses.street"} masks the {@code street} field inside every
 * element of the {@code addresses} array.
 *
 * <p><strong>Type-safe masking</strong></p>
 * Replacement values are chosen to match the original JSON node type so that
 * {@code ObjectMapper.treeToValue} can deserialise the masked tree back to
 * the original Java class without type-coercion errors:
 * <ul>
 *   <li>Strings  → {@code "***"}</li>
 *   <li>Integers / longs → {@code 0}</li>
 *   <li>Floats / doubles / decimals → {@code 0.0}</li>
 *   <li>Booleans → {@code false}</li>
 *   <li>Objects, arrays, nulls → {@code null}</li>
 * </ul>
 */
public class MaskingJackson implements MaskingStrategy<MaskingJacksonConfig> {

    private ObjectMapper mapper;

    public MaskingJackson() {
        this.mapper = new ObjectMapper();
    }

    public MaskingJackson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String getName() {
        return "jackson";
    }

    @Override
    public Object mask(Object obj, Set<ConsentScope> deniedScopes) {
        if (obj == null || deniedScopes == null || deniedScopes.isEmpty()) {
            return obj;
        }

        // Extract the qualified field-name paths we need to mask.
        Set<String> deniedFields = deniedScopes
            .stream()
            .map(ConsentScope::fieldName)
            .collect(Collectors.toSet());

        try {
            // 1. Convert object to a mutable JSON tree
            JsonNode rootNode = mapper.valueToTree(obj);

            // 2. Traverse and apply masking with path tracking
            maskNode(rootNode, deniedFields, "");

            // 3. Convert back to the original object type
            return mapper.treeToValue(rootNode, obj.getClass());
        } catch (OperioServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioServiceException(
                "Operio Masking Failure: Unable to mask object via Jackson",
                e
            );
        }
    }

    /**
     * Recursively walks the JSON tree, building a qualified dot-path for each
     * node, and replaces any field whose full path appears in
     * {@code deniedFields} with a type-safe masked value.
     *
     * @param node         the current JSON node
     * @param deniedFields set of qualified dot-path field names to mask
     * @param currentPath  the dot-path leading to {@code node} (empty string
     *                     at the root level)
     */
    private void maskNode(
        JsonNode node,
        Set<String> deniedFields,
        String currentPath
    ) {
        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();

                // Build the fully-qualified path for this field.
                String fieldPath = currentPath.isEmpty()
                    ? entry.getKey()
                    : currentPath + "." + entry.getKey();

                if (deniedFields.contains(fieldPath)) {
                    // TODO: Once AccessLevel is added to ConsentScope,
                    //  evaluate PARTIAL vs FULL here.
                    // For now, default to FULL redaction with type-safe
                    //  replacement.
                    entry.setValue(maskValue(entry.getValue()));
                } else {
                    // Recurse into nested objects / arrays, carrying the
                    // qualified path forward.
                    maskNode(entry.getValue(), deniedFields, fieldPath);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode childNode : arrayNode) {
                // Array elements share the parent field's path context.
                // e.g. for "addresses": [ {…}, {…} ] each element is
                // matched against paths prefixed with "addresses".
                maskNode(childNode, deniedFields, currentPath);
            }
        }
    }

    /**
     * Returns a type-appropriate masked replacement for the given JSON node.
     * <p>
     * This ensures that when the masked JSON tree is deserialized back to the
     * original Java type, Jackson can coerce the replacement value into the
     * target field type without throwing a deserialization exception.
     *
     * @param original the original node value to be masked
     * @return a safe replacement node of the same general JSON type
     */
    private static JsonNode maskValue(JsonNode original) {
        if (original == null || original.isNull()) {
            return NullNode.getInstance();
        } else if (original.isTextual()) {
            return new TextNode("***");
        } else if (
            original.isInt() || original.isLong() || original.isShort()
        ) {
            return IntNode.valueOf(0);
        } else if (
            original.isFloat() ||
            original.isDouble() ||
            original.isBigDecimal() ||
            original.isBigInteger()
        ) {
            return DoubleNode.valueOf(0.0);
        } else if (original.isBoolean()) {
            return BooleanNode.FALSE;
        } else {
            // Objects, arrays, and anything else — replace with null
            return NullNode.getInstance();
        }
    }

    @Override
    public Class<MaskingJacksonConfig> getConfigurationType() {
        return MaskingJacksonConfig.class;
    }

    @Override
    public void configure(MaskingJacksonConfig config) {
        if (config != null && config.objectMapper() != null) {
            this.mapper = config.objectMapper();
        }
    }
}
