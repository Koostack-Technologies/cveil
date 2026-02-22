package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;

/**
 * Policy administration service — grants, revokes, and manages consent.
 *
 * <p>In addition to the core {@link #grantConsent}/{@link #revokeConsent}
 * methods, this interface defines <strong>schema registration</strong>
 * methods ({@link #registerField}, {@link #registerCategory},
 * {@link #linkFieldToCategory}) that allow callers to populate the consent
 * admin schema with field and category metadata discovered by a
 * {@link SensitiveDiscovery} plugin.
 *
 * <p>The registration methods use <strong>upsert semantics</strong> — calling
 * them for an entity/field/category that already exists updates the
 * description but preserves the existing row. This makes the full
 * discover → register pipeline safely idempotent.
 *
 * @param <T> the plugin configuration type
 */
public interface ConsentAdminService<T> extends Plugin<T> {
    void grantConsent(String subject, ConsentScope accessRequest);
    void revokeConsent(String subject, ConsentScope accessRequest);

    /**
     * Grants consent with an expiration timestamp.
     *
     * <p>Default implementation delegates to
     * {@link #grantConsent(String, ConsentScope)}, ignoring the expiration.
     * Implementations that support expiration should override this method.
     *
     * @param subject       the data subject identifier
     * @param accessRequest the scope of the consent grant
     * @param expiresAt     when the consent expires, or {@code null} for no
     *                      expiration
     */
    default void grantConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        grantConsent(subject, accessRequest);
    }

    /**
     * Revokes consent with an expiration timestamp.
     *
     * <p>Default implementation delegates to
     * {@link #revokeConsent(String, ConsentScope)}, ignoring the expiration.
     * Implementations that support expiration should override this method.
     *
     * @param subject       the data subject identifier
     * @param accessRequest the scope of the consent revocation
     * @param expiresAt     when the revocation expires, or {@code null} for
     *                      no expiration
     */
    default void revokeConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        revokeConsent(subject, accessRequest);
    }

    // -----------------------------------------------------------------------
    // Schema registration — used by SensitiveDiscovery pipeline
    // -----------------------------------------------------------------------

    /**
     * Registers a sensitive field in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a field with the same name and entity
     * already exists, its description is updated but the row is preserved.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration (e.g. jOOQ-backed)
     * should override this method.
     *
     * @param fieldName   the field name (e.g. {@code "email"},
     *                    {@code "home.street"})
     * @param entityName  the owning entity name (e.g. {@code "User"}), or
     *                    {@code null} for entity-less fields
     * @param description optional human-readable description, or {@code null}
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     */
    default void registerField(
        String fieldName,
        String entityName,
        String description
    ) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }

    /**
     * Registers a consent category in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a category with the same name already
     * exists, its description is updated but the row is preserved.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration should override this.
     *
     * @param categoryName the category name (e.g. {@code "PII"},
     *                     {@code "HEALTH"})
     * @param description  optional human-readable description, or {@code null}
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     */
    default void registerCategory(String categoryName, String description) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }

    /**
     * Links a field to a category in the consent admin schema.
     *
     * <p>Uses idempotent semantics — if the link already exists, this is a
     * no-op. Both the field and the category must already be registered
     * (via {@link #registerField} and {@link #registerCategory}).
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration should override this.
     *
     * @param fieldName    the field name to link
     * @param entityName   the owning entity name, or {@code null} for
     *                     entity-less fields
     * @param categoryName the category name to link to
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     * @throws IllegalArgumentException if the field or category does not exist
     */
    default void linkFieldToCategory(
        String fieldName,
        String entityName,
        String categoryName
    ) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }
}
