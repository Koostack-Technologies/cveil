package com.koostack.operio.core.service;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;

/**
 * Pluggable SPI for dual-write synchronization between the relational
 * consent admin layer and a cache/decision layer (e.g. Redis).
 *
 * <p>Implementations coordinate the DB write (via {@link ConsentAdminService})
 * and the cache write (via decision-layer repositories) in a single call,
 * ensuring both stores stay in sync.
 *
 * <p>The framework discovers implementations via the same plugin mechanism
 * used for {@link ConsentAdminService} and
 * {@link ConsentDecisionService}. Callers interact with this interface
 * instead of calling the admin and decision layers separately when both
 * must be updated atomically (best-effort).
 *
 * <h2>Error Handling Contract</h2>
 * <p>The relational DB write is the source of truth. If the cache write
 * fails, implementations should catch the error and log it rather than
 * propagating the exception — the DB row is never rolled back on cache
 * failure. The stale cache entry will be corrected by a future
 * reconciliation job or a subsequent successful sync.
 *
 * @param <T> the configuration type for this plugin
 * @see ConsentAdminService
 * @see ConsentDecisionService
 * @deprecated The dual-write pattern is replaced by per-tenant Redis Streams
 *     with event-driven projections. The multi-tenant REST API deployment
 *     uses {@code ConsentAdminService} for writes and stream consumers for
 *     cache updates. The embedded bundle retains synchronous dual-write as
 *     an internal implementation detail. This interface will be removed in
 *     a future release.
 */
@Deprecated(forRemoval = true)
public interface ConsentSyncService<T> extends Plugin<T> {

    /**
     * Grants consent for the given subject and scope, writing to both the
     * relational store and the cache layer.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope (field-level or category-level)
     * @param expiresAt when this consent grant expires, or {@code null}
     *                  for no expiration
     */
    void syncGrant(String subject, ConsentScope scope, Instant expiresAt);

    /**
     * Revokes consent for the given subject and scope, writing to both the
     * relational store and the cache layer.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope (field-level or category-level)
     * @param expiresAt when this consent revocation expires, or {@code null}
     *                  for no expiration
     */
    void syncRevoke(String subject, ConsentScope scope, Instant expiresAt);
}
