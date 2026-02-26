package com.koostack.operio.core.data.consent;

import com.koostack.operio.core.models.CategoryInfo;
import com.koostack.operio.core.models.ConsentAuditEntry;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.EntityInfo;
import com.koostack.operio.core.models.FieldInfo;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.PurposeInfo;
import com.koostack.operio.core.models.RegulationInfo;
import com.koostack.operio.core.spi.Plugin;
import java.util.List;

public interface ConsentAdminQueryService<T> extends Plugin<T> {

    // -----------------------------------------------------------------------
    // Schema query — used by REST API and admin dashboards
    // -----------------------------------------------------------------------

    /**
     * Lists all registered sensitive fields.
     *
     * @return an unmodifiable list of all registered fields, possibly empty
     */
    List<FieldInfo> listFields();

    /**
     * Lists all registered consent categories.
     *
     * @return an unmodifiable list of all registered categories, possibly empty
     */
    List<CategoryInfo> listCategories();

    /**
     * Lists all distinct entities that have registered fields.
     *
     * @return an unmodifiable list of all entities, possibly empty
     */
    List<EntityInfo> listEntities();

    /**
     * Lists all registered consent purposes.
     *
     * @return an unmodifiable list of all registered purposes, possibly empty
     */
    List<PurposeInfo> listPurposes();

    /**
     * Lists all registered regulations.
     *
     * @return an unmodifiable list of all registered regulations, possibly empty
     */
    List<RegulationInfo> listRegulations();

    /**
     * Finds all consent policy entries for a given data subject.
     *
     * <p>Returns a flat summary of every policy row matching the subject,
     * including field, entity, category, purpose, status, and expiration.
     *
     * @param subjectId the data subject identifier to search for
     * @return an unmodifiable list of matching policy summaries, possibly empty
     */
    List<PolicySummaryEntry> findPoliciesBySubject(String subjectId);

    /**
     * Counts the number of consent policies for the given subject.
     *
     * <p>This is a lightweight count query — it does not fetch the full
     * policy rows. Useful for populating DSAR deletion receipts before
     * performing bulk erasure.
     *
     * @param subjectId the data subject identifier
     * @return the number of consent policies for the subject
     */
    int countPoliciesBySubject(String subjectId);

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
     * @param subjectId the data subject identifier to retrieve history for
     * @return an unmodifiable list of audit entries, most recent first,
     *         possibly empty
     */
    List<ConsentAuditEntry> getConsentHistory(String subjectId);

    /**
     * Returns the consent change history for a subject filtered to a
     * specific scope (field + entity + purpose combination), ordered by
     * timestamp descending (most recent first).
     *
     * @param subjectId the data subject identifier to retrieve history for
     * @param scope     the consent scope to filter by
     * @return an unmodifiable list of matching audit entries, most recent
     *         first, possibly empty
     */
    List<ConsentAuditEntry> getConsentHistory(String subjectId, ConsentScope scope);
}
