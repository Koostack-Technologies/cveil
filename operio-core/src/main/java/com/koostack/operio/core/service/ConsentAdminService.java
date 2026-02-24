package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.CategoryInfo;
import com.koostack.operio.core.Models.ConsentAuditEntry;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.EntityInfo;
import com.koostack.operio.core.Models.FieldInfo;
import com.koostack.operio.core.Models.PolicySummaryEntry;
import com.koostack.operio.core.Models.PurposeInfo;
import com.koostack.operio.core.Models.RegulationInfo;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;
import java.util.List;

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
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Only implementations that support bulk erasure (e.g. jOOQ-backed relational
     * stores) should override this method. Purely in-memory implementations
     * (Caffeine, Redis) do not support this operation.
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support bulk erasure
     * @see #revokeConsent(String, ConsentScope)
     */
    default void revokeAllConsent(String subject) {
        throw new UnsupportedOperationException(
            "Bulk erasure (revokeAllConsent) not supported by " + getName()
        );
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

    // -----------------------------------------------------------------------
    // Purpose & regulation registration
    // -----------------------------------------------------------------------

    /**
     * Registers a consent purpose in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a purpose with the same name already
     * exists, its description is updated but the row is preserved.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration should override this.
     *
     * @param purposeName the purpose identifier (e.g. {@code "marketing"},
     *                    {@code "analytics"})
     * @param description optional human-readable description, or {@code null}
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     */
    default void registerPurpose(String purposeName, String description) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }

    /**
     * Registers a regulation in the consent admin schema.
     *
     * <p>Uses upsert semantics — if a regulation with the same name already
     * exists, its fields are updated but the row is preserved.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration should override this.
     *
     * @param regulationName short identifier (e.g. {@code "GDPR"}, {@code "CCPA"})
     * @param fullName       full legal name (e.g. {@code "General Data Protection
     *                       Regulation"})
     * @param jurisdiction   geographic jurisdiction (e.g. {@code "EU"}, {@code "US-CA"})
     * @param description    optional human-readable description, or {@code null}
     * @param url            optional URL to the regulation text, or {@code null}
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     */
    default void registerRegulation(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }

    /**
     * Links a purpose to a regulation with a legal basis in the consent
     * admin schema.
     *
     * <p>Uses idempotent semantics — if the link already exists, this is a
     * no-op. Both the purpose and the regulation must already be registered
     * (via {@link #registerPurpose} and {@link #registerRegulation}).
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema registration should override this.
     *
     * @param purposeName    the purpose to link
     * @param regulationName the regulation to link to
     * @param legalBasis     the legal basis for processing under this
     *                       regulation (e.g. {@code "consent"},
     *                       {@code "legitimate_interest"}, {@code "contract"})
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema registration
     * @throws IllegalArgumentException if the purpose or regulation does not
     *                                  exist
     */
    default void linkPurposeToRegulation(
        String purposeName,
        String regulationName,
        String legalBasis
    ) {
        throw new UnsupportedOperationException(
            "Schema registration not supported by " + getName()
        );
    }

    // -----------------------------------------------------------------------
    // Schema query — used by REST API and admin dashboards
    // -----------------------------------------------------------------------

    /**
     * Lists all registered sensitive fields.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries (e.g. jOOQ-backed) should
     * override this method.
     *
     * @return an unmodifiable list of all registered fields, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<FieldInfo> listFields() {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

    /**
     * Lists all registered consent categories.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @return an unmodifiable list of all registered categories, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<CategoryInfo> listCategories() {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

    /**
     * Lists all distinct entities that have registered fields.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @return an unmodifiable list of all entities, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<EntityInfo> listEntities() {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

    /**
     * Lists all registered consent purposes.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @return an unmodifiable list of all registered purposes, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<PurposeInfo> listPurposes() {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

    /**
     * Lists all registered regulations.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @return an unmodifiable list of all registered regulations, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<RegulationInfo> listRegulations() {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

    /**
     * Finds all consent policy entries for a given data subject.
     *
     * <p>Returns a flat summary of every policy row matching the subject,
     * including field, entity, category, purpose, status, and expiration.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @param subjectId the data subject identifier to search for
     * @return an unmodifiable list of matching policy summaries, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support schema queries
     */
    default List<PolicySummaryEntry> findPoliciesBySubject(String subjectId) {
        throw new UnsupportedOperationException(
            "Schema query not supported by " + getName()
        );
    }

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
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support DSAR erasure (e.g. jOOQ-backed relational
     * stores) should override this method.
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @return the number of audit log entries deleted
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support audit log deletion
     */
    default int deleteAuditLogBySubject(String subject) {
        throw new UnsupportedOperationException(
            "Audit log deletion not supported by " + getName()
        );
    }

    /**
     * Counts the number of consent policies for the given subject.
     *
     * <p>This is a lightweight count query — it does not fetch the full
     * policy rows. Useful for populating DSAR deletion receipts before
     * performing bulk erasure via {@link #revokeAllConsent(String)}.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support schema queries should override this.
     *
     * @param subjectId the data subject identifier
     * @return the number of consent policies for the subject
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support this query
     */
    default int countPoliciesBySubject(String subjectId) {
        throw new UnsupportedOperationException(
            "Policy count query not supported by " + getName()
        );
    }

    // -----------------------------------------------------------------------
    // Consent audit history
    // -----------------------------------------------------------------------

    /**
     * Returns the full consent change history for a subject, ordered by
     * timestamp descending (most recent first).
     *
     * <p>Each entry represents a single grant or revoke event with the
     * actor, timestamp, and request metadata. The audit log is append-only
     * — it captures the full history even when the current-state
     * {@code consent_policy} row has been overwritten.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support audit history should override this.
     *
     * @param subjectId the data subject identifier to retrieve history for
     * @return an unmodifiable list of audit entries, most recent first,
     *         possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support audit history
     */
    default List<ConsentAuditEntry> getConsentHistory(String subjectId) {
        throw new UnsupportedOperationException(
            "Consent audit history not supported by " + getName()
        );
    }

    /**
     * Returns the consent change history for a subject filtered to a
     * specific scope (field + entity + purpose combination), ordered by
     * timestamp descending (most recent first).
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support audit history should override this.
     *
     * @param subjectId the data subject identifier to retrieve history for
     * @param scope     the consent scope to filter by
     * @return an unmodifiable list of matching audit entries, most recent
     *         first, possibly empty
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support audit history
     */
    default List<ConsentAuditEntry> getConsentHistory(
        String subjectId,
        ConsentScope scope
    ) {
        throw new UnsupportedOperationException(
            "Consent audit history not supported by " + getName()
        );
    }
}
