package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.SUBJECT;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.SubjectDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Subject;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** Read-only repository for the {@code subject} table. */
public class SubjectRepository {

    private final DSLContext dsl;
    private final SubjectDao dao;

    public SubjectRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new SubjectDao(dsl.configuration());
    }

    public SubjectDao dao() {
        return dao;
    }

    public Optional<Subject> findByName(String subjectName) {
        return dsl.selectFrom(SUBJECT)
                .where(SUBJECT.SUBJECT_NAME.eq(subjectName))
                .fetchOptionalInto(Subject.class);
    }

    public List<Subject> findAll() {
        return dsl.selectFrom(SUBJECT).orderBy(SUBJECT.SUBJECT_NAME).fetchInto(Subject.class);
    }
}
