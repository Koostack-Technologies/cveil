package com.koostack.operio.plugin.masking.jackson;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.service.MaskingStrategy;
import com.koostack.operio.core.service.SensitiveFieldResolver;

public class MaskingJackson implements MaskingStrategy {

    private final ObjectMapper mapper;

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

        // Extract just the field names we need to mask
        Set<String> deniedFields = deniedScopes.stream()
                .map(ConsentScope::fieldName)
                .collect(Collectors.toSet());

        try {
            // 1. Convert object to a mutable JSON tree
            JsonNode rootNode = mapper.valueToTree(obj);
            
            // 2. Traverse and apply masking
            maskNode(rootNode, deniedFields);
            
            // 3. Convert back to the original object type
            return mapper.treeToValue(rootNode, obj.getClass());
        } catch (Exception e) {
            throw new RuntimeException("Operio Masking Failure: Unable to mask object via Jackson", e);
        }
    }

    private void maskNode(JsonNode node, Set<String> deniedFields) {
        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
            
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                
                if (deniedFields.contains(entry.getKey())) {
                    // TODO: Once AccessLevel is added to ConsentScope, evaluate PARTIAL vs FULL here.
                    // For now, default to FULL redaction.
                    entry.setValue(new TextNode("***"));
                } else {
                    // Recurse into nested objects
                    maskNode(entry.getValue(), deniedFields);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode childNode : arrayNode) {
                maskNode(childNode, deniedFields);
            }
        }
    }
}
