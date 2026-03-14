package com.koostack.cveil.core.util;

/**
 * Normalizes field names and entity names to a canonical {@code snake_case} form so that consent
 * graph lookups match regardless of the casing convention used by the data source.
 *
 * <p>Supported input conventions:
 *
 * <ul>
 *   <li>{@code camelCase} → {@code camel_case}
 *   <li>{@code PascalCase} → {@code pascal_case}
 *   <li>{@code SCREAMING_SNAKE_CASE} → {@code screaming_snake_case}
 *   <li>{@code kebab-case} → {@code kebab_case}
 *   <li>{@code dot.case} → {@code dot_case}
 *   <li>{@code snake_case} → {@code snake_case} (no-op)
 *   <li>Mixed formats like {@code myField_Name} → {@code my_field_name}
 * </ul>
 *
 * <p>The result is always lowercase with underscores as separators.
 */
public final class FieldNameNormalizer {

    private FieldNameNormalizer() {}

    /**
     * Converts any common naming convention to canonical {@code snake_case}.
     *
     * @param name the field or entity name (nullable)
     * @return the normalized snake_case name, or {@code null} if input is null/blank
     */
    public static String toSnakeCase(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // Replace hyphens and dots with underscores first
        String s = name.replace('-', '_').replace('.', '_');

        StringBuilder sb = new StringBuilder(s.length() + 8);
        char prev = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '_') {
                // Collapse multiple underscores
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            } else if (Character.isUpperCase(c)) {
                // Insert underscore before uppercase if:
                //   1. Not at the start
                //   2. Previous char was lowercase (camelCase boundary)
                //   3. Or previous char was uppercase but next is lowercase (acronym end: "HTMLParser" → "html_parser")
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    boolean prevLower = Character.isLowerCase(prev);
                    boolean nextLower =
                            (i + 1 < s.length())
                                    && Character.isLetter(s.charAt(i + 1))
                                    && Character.isLowerCase(s.charAt(i + 1));
                    if (prevLower || (Character.isUpperCase(prev) && nextLower)) {
                        sb.append('_');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
            prev = c;
        }

        // Trim leading/trailing underscores
        String result = sb.toString();
        while (result.startsWith("_")) result = result.substring(1);
        while (result.endsWith("_")) result = result.substring(0, result.length() - 1);

        return result.isEmpty() ? null : result;
    }

    /**
     * Normalizes a field or entity name for consent graph lookup. If the input is null or blank,
     * returns it as-is (the caller should handle defaults like {@code __default__}).
     *
     * @param name the name to normalize
     * @return the canonical snake_case form, or the original value if null/blank
     */
    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return toSnakeCase(name);
    }
}
