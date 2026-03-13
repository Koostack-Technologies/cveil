package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.service.ConsentDecisionService;

/**
 * Query actuator that counts cached consent-decision edge-property keys for a subject. Uses
 * SCAN-based key enumeration on the backing store (typically Redis).
 */
public class CountSubjectCacheKeysQueryActuator
        implements QueryActuator<
                CountSubjectCacheKeysQueryActuator.CountSubjectCacheKeysQuery, Long> {

    private final ConsentDecisionService<?> consentService;

    public CountSubjectCacheKeysQueryActuator(ConsentDecisionService<?> consentService) {
        this.consentService = consentService;
    }

    /**
     * Query input for counting a subject's cached consent-decision keys.
     *
     * @param subject the data subject identifier
     */
    public record CountSubjectCacheKeysQuery(String subject) {}

    @Override
    public Long execute(CountSubjectCacheKeysQuery query) {
        return consentService.countCachedKeysForSubject(query.subject());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
