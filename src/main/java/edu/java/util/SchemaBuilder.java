package edu.java.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal helper to build JSON Schema maps for tool input schemas. The SDK serializes these directly into the protocol message.
 */
public final class SchemaBuilder {

    private SchemaBuilder() {
    }

    /**
     * Create an object schema with the given required string properties.
     * 
     * @param description            of {@code Parameters}
     * @param mapParameterProperties {@link Map}&lt;parameterName, parameterDescription&gt;
     * @param listParameterNames     {@link List}&lt;parameterName&gt;
     * @return {@link Map}&lt;objectName, objectValue&gt;
     */
    public static Map<String, Object> objectSchema(final String description, final Map<String, String> mapParameterProperties,
            final List<String> listParameterNames) {
        Map<String, Object> properties = new HashMap<>();
        for (var entry : mapParameterProperties.entrySet()) {
            properties.put(entry.getKey(), Map.of("type", "string", "description", entry.getValue()));
        }
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", listParameterNames);
        return schema;
    }

    /**
     * Convenience: schema with a single required string property.
     * 
     * @param parameterName
     * @param parameterDescription
     * @return {@link Map}&lt;"Parameters", {@link Map}&gt;
     */
    public static Map<String, Object> singleStringParameter(final String parameterName, final String parameterDescription) {
        return objectSchema("Parameters", Map.of(parameterName, parameterDescription), List.of(parameterName));
    }

}
