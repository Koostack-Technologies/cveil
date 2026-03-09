package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.service.ConsentDecisionService;

/**
 * Query actuator that deletes all cached consent-decision edge-property keys for a subject and
 * returns the number of keys removed. Uses SCAN-based key enumeration and pipelined DEL on the
 * backing store (typically Redis).
 */
public class DeleteSubjectCacheKeysQueryActuator
        implements QueryActuator<
                DeleteSubjectCacheKeysQueryActuator.DeleteSubjectCacheKeysQuery, Long> {

    private final ConsentDecisionService<?> consentService;

    public DeleteSubjectCacheKeysQueryActuator(ConsentDecisionService<?> consentService) {
        this.consentService = consentService;
    }

    /**
     * Query input for deleting a subject's cached consent-decision keys.
     *
     * @param subject the data subject identifier
     */
    public record DeleteSubjectCacheKeysQuery(String subject) {}

    @Override
    public Long execute(DeleteSubjectCacheKeysQuery query) {
        return consentService.deleteCachedKeysForSubject(query.subject());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
