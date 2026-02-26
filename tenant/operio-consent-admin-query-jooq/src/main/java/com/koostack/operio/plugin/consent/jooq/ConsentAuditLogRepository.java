package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.ConsentAuditLogDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentAuditLog;

import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.List;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_AUDIT_LOG;

/**
 * Read-only repository for the {@code consent_audit_log} table.
 */
public class ConsentAuditLogRepository {

    private final DSLContext dsl;
    private final ConsentAuditLogDao dao;

    public ConsentAuditLogRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new ConsentAuditLogDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public ConsentAuditLogDao dao() {
        return dao;
    }

    public List<ConsentAuditLog> findBySubject(String subjectId) {
        return dsl.selectFrom(CONSENT_AUDIT_LOG)
                  .where(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
                  .orderBy(CONSENT_AUDIT_LOG.CREATED_AT.desc())
                  .fetchInto(ConsentAuditLog.class);
    }

    public List<ConsentAuditLog> findBySubjectAndScope(
        String subjectId,
        Long fieldId,
        Long categoryId,
        Long purposeId
    ) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId));

        if (fieldId != null) {
            conditions.add(CONSENT_AUDIT_LOG.FIELD_ID.eq(fieldId));
        }
        if (categoryId != null) {
            conditions.add(CONSENT_AUDIT_LOG.CATEGORY_ID.eq(categoryId));
        }
        if (purposeId != null) {
            conditions.add(CONSENT_AUDIT_LOG.PURPOSE_ID.eq(purposeId));
        }

        return dsl.selectFrom(CONSENT_AUDIT_LOG)
                  .where(conditions)
                  .orderBy(CONSENT_AUDIT_LOG.CREATED_AT.desc())
                  .fetchInto(ConsentAuditLog.class);
    }
}
