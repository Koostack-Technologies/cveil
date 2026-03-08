package com.koostack.operio.common.keycloak;

/**
 * Strategy for mapping a caller-supplied identifier to a Keycloak realm name.
 *
 * <p>Platform callers pass the realm ID directly ({@link #identity()}), while tenant
 * callers prefix with {@code t_} ({@link #tenantPrefix()}).
 */
@FunctionalInterface
public interface RealmResolver {

    /**
     * Map the caller-supplied identifier to a Keycloak realm name.
     *
     * @param id caller-supplied identifier (e.g. realm ID or tenant ID)
     * @return the Keycloak realm name
     */
    String toRealmId(String id);

    /**
     * Identity resolver — the input IS the realm ID (used by platform IAM).
     */
    static RealmResolver identity() {
        return id -> id;
    }

    /**
     * Tenant-prefix resolver — maps {@code tenantId} → {@code t_<tenantId>}.
     */
    static RealmResolver tenantPrefix() {
        return id -> "t_" + id;
    }
}

