package com.koostack.operio.plugin.jooq.common;

public enum Dialect {
    POSTGRES,
    POSTGRESQL,
    SQLITE,
    H2;

    public static Dialect fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
