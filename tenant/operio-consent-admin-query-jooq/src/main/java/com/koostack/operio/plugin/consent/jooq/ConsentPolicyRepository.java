package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_POLICY;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.ConsentPolicyDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Read-only repository for the {@code consent_policy} table.
 */
public class ConsentPolicyRepository {

    private final DSLContext dsl;
    private final ConsentPolicyDao dao;

    public ConsentPolicyRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new ConsentPolicyDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public ConsentPolicyDao dao() {
        return dao;
    }

    public List<ConsentPolicy> findBySubject(String subjectId) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .fetchInto(ConsentPolicy.class);
    }

    public int countBySubject(String subjectId) {
        return dsl
            .selectCount()
            .from(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .fetchOne(0, int.class);
    }

    public List<ConsentPolicy> findBySubjectAndPurpose(
        String subjectId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchInto(ConsentPolicy.class);
    }

    public Optional<ConsentPolicy> findBySubjectAndFieldAndPurpose(
        String subjectId,
        Long fieldId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.FIELD_ID.eq(fieldId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchOptionalInto(ConsentPolicy.class);
    }

    public Optional<ConsentPolicy> findBySubjectAndCategoryAndPurpose(
        String subjectId,
        Long categoryId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.CATEGORY_ID.eq(categoryId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchOptionalInto(ConsentPolicy.class);
    }

    public List<ConsentPolicy> findActiveGrantsBySubject(String subjectId) {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    public List<ConsentPolicy> findActiveGrantsBySubjectAndPurpose(
        String subjectId,
        Long purposeId
    ) {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .and(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    public List<ConsentPolicy> findAll() {
        return dsl.selectFrom(CONSENT_POLICY).fetchInto(ConsentPolicy.class);
    }

    public List<ConsentPolicy> findAllActive() {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    public List<ConsentPolicy> findAllBySubject(String subjectId) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .fetchInto(ConsentPolicy.class);
    }
}
