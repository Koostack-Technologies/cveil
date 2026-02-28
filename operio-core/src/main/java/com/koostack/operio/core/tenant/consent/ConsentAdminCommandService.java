package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;

public interface ConsentAdminCommandService<T> extends Plugin<T> {

    void grantConsentCommand(String subject, ConsentScope accessRequest);

    void revokeConsentCommand(String subject, ConsentScope accessRequest);

    /**
     * Grants consent with an expiration timestamp.
     *
     * <p>Default implementation delegates to
     * {@link #grantConsentCommand(String, ConsentScope)}, ignoring the expiration.
     * Implementations that support expiration should override this method.
     *
     * @param subject       the data subject identifier
     * @param accessRequest the scope of the consent grant
     * @param expiresAt     when the consent expires, or {@code null} for no
     *                      expiration
     */
    default void grantConsentCommand(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        grantConsentCommand(subject, accessRequest);
    }

    /**
     * Revokes consent with an expiration timestamp.
     *
     * <p>Default implementation delegates to
     * {@link #revokeConsentCommand(String, ConsentScope)}, ignoring the expiration.
     * Implementations that support expiration should override this method.
     *
     * @param subject       the data subject identifier
     * @param accessRequest the scope of the consent revocation
     * @param expiresAt     when the revocation expires, or {@code null} for
     *                      no expiration
     */
    default void revokeConsentCommand(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        revokeConsentCommand(subject, accessRequest);
    }

    /**
     * Revokes all consent policies for a given subject across all purposes and scopes.
     *
     * <p>This is a bulk erasure operation used for GDPR Article 17 (right to erasure)
     * and CCPA §1798.105 (right to deletion). It atomically removes all active
     * consent records for the subject from the policy store. The operation is:
     * <ul>
     *   <li><strong>Atomic</strong> — either all policies are revoked, or none are.
     *       Implementation should use a single transaction.</li>
     *   <li><strong>Audited</strong> — the revocation must be logged to the consent
     *       audit trail (or a separate DSAR audit table) for forensic compliance.</li>
     *   <li><strong>Idempotent</strong> — calling this multiple times for the same
     *       subject is safe; subsequent calls are no-ops.</li>
     * </ul>
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @see #revokeConsentCommand(String, ConsentScope)
     */
    void revokeAllConsentCommand(String subject);

    // -----------------------------------------------------------------------
    // Schema registration — used by SensitiveDiscovery pipeline
    // -----------------------------------------------------------------------

    /**
     * Registers a sensitive field in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a field with the same name and entity
     * already exists, its description is updated but the row is preserved.
     *
     * @param fieldName   the field name (e.g. {@code "email"},
     *                    {@code "home.street"})
     * @param entityName  the owning entity name (e.g. {@code "User"}), or
     *                    {@code null} for entity-less fields
     * @param description optional human-readable description, or {@code null}
     */
    void registerFieldCommand(String fieldName, String entityName, String description);

    /**
     * Registers a consent category in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a category with the same name already
     * exists, its description is updated but the row is preserved.
     *
     * @param categoryName the category name (e.g. {@code "PII"},
     *                     {@code "HEALTH"})
     * @param description  optional human-readable description, or {@code null}
     */
    void registerCategoryCommand(String categoryName, String description);

    /**
     * Links a field to a category in the consent admin schema.
     *
     * <p>Uses idempotent semantics — if the link already exists, this is a
     * no-op. Both the field and the category must already be registered
     * (via {@link #registerFieldCommand} and {@link #registerCategoryCommand}).
     *
     * @param fieldName    the field name to link
     * @param entityName   the owning entity name, or {@code null} for
     *                     entity-less fields
     * @param categoryName the category name to link to
     * @throws IllegalArgumentException if the field or category does not exist
     */
    void linkFieldToCategoryCommand(String fieldName, String entityName, String categoryName);

    // -----------------------------------------------------------------------
    // Purpose & regulation registration
    // -----------------------------------------------------------------------

    /**
     * Registers a consent purpose in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a purpose with the same name already
     * exists, its description is updated but the row is preserved.
     *
     * @param purposeName the purpose identifier (e.g. {@code "marketing"},
     *                    {@code "analytics"})
     * @param description optional human-readable description, or {@code null}
     */
    void registerPurposeCommand(String purposeName, String description);

    /**
     * Registers a regulation in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a regulation with the same name already
     * exists, its fields are updated but the row is preserved.
     *
     * @param regulationName short identifier (e.g. {@code "GDPR"}, {@code "CCPA"})
     * @param fullName       full legal name (e.g. {@code "General Data Protection
     *                       Regulation"})
     * @param jurisdiction   geographic jurisdiction (e.g. {@code "EU"}, {@code "US-CA"})
     * @param description    optional human-readable description, or {@code null}
     * @param url            optional URL to the regulation text, or {@code null}
     */
    void registerRegulationCommand(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    );

    /**
     * Links a purpose to a regulation with a legal basis in the consent
     * admin schema.
     *
     * <p>Uses idempotent semantics — if the link already exists, this is a
     * no-op. Both the purpose and the regulation must already be registered
     * (via {@link #registerPurposeCommand} and {@link #registerRegulationCommand}).
     *
     * @param purposeName    the purpose to link
     * @param regulationName the regulation to link to
     * @param legalBasis     the legal basis for processing under this
     *                       regulation (e.g. {@code "consent"},
     *                       {@code "legitimate_interest"}, {@code "contract"})
     * @throws IllegalArgumentException if the purpose or regulation does not
     *                                  exist
     */
    void linkPurposeToRegulationCommand(String purposeName, String regulationName, String legalBasis);

    // -----------------------------------------------------------------------
    // DSAR erasure support
    // -----------------------------------------------------------------------

    /**
     * Deletes all consent audit log entries for the given subject.
     *
     * <p>This is a destructive operation intended only for DSAR (Data Subject
     * Access Request) erasure compliance (GDPR Article 17). The audit log is
     * designed to be append-only under normal circumstances; this method
     * should only be called as part of a full subject data erasure flow.
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @return the number of audit log entries deleted, or {@code -1} if the
     *         deletion is eventual (e.g. event-sourced implementations that
     *         publish a deletion-requested event)
     */
    int deleteAuditLogBySubjectCommand(String subject);
}
