package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_LEDGER;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import org.jooq.DSLContext;

/**
 * Query actuator that counts consent ledger entries for a subject.
 */
public class CountSubjectConsentEntriesQueryActuator
        implements QueryActuator<
                CountSubjectConsentEntriesQueryActuator.CountSubjectConsentEntriesQuery, Integer> {

    private final DSLContext dsl;

    public CountSubjectConsentEntriesQueryActuator(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Query input for counting a subject's consent entries.
     *
     * @param subjectId the data subject identifier
     */
    public record CountSubjectConsentEntriesQuery(String subjectId) {}

    @Override
    public Integer execute(CountSubjectConsentEntriesQuery query) {
        return dsl.selectCount()
                .from(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.SUBJECT_ID.eq(query.subjectId()))
                .fetchOneInto(int.class);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
