package com.koostack.operio.core.models;

import java.time.Instant;
import java.util.Set;
/**
 * Immutable snapshot of an API key's metadata.
 *
 * <p>Returned by {@link com.koostack.operio.core.service.PlatformAdminService}
 * list/query methods. Never contains the raw key or its hash — only the
 * non-sensitive prefix and associated metadata.
 *
 * @param keyPrefix  the first 8 characters of the raw key (used for
 *                   display and revocation)
 * @param tenantId   the owning tenant
 * @param subject    the subject (user/service account) this key represents
 * @param roles      the roles/authorities granted to this key
 * @param status     key status ({@code "ACTIVE"}, {@code "REVOKED"})
 * @param createdAt  when the key was created
 */
public record ApiKeyInfo(
    String keyPrefix,
    String tenantId,
    String subject,
    Set<String> roles,
    String status,
    Instant createdAt
) {}
