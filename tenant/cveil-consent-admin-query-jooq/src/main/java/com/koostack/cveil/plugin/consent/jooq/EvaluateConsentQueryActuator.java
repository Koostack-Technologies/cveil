package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.service.ConsentDecisionService;
import java.util.Set;

/**
 * Query actuator that evaluates consent for a subject against a set of scopes. Delegates to the
 * configured {@link ConsentDecisionService} (typically Redis-backed).
 */
public class EvaluateConsentQueryActuator
        implements QueryActuator<
                EvaluateConsentQueryActuator.EvaluateConsentQuery, ConsentResponse> {

    private final ConsentDecisionService<?> consentService;

    public EvaluateConsentQueryActuator(ConsentDecisionService<?> consentService) {
        this.consentService = consentService;
    }

    /**
     * Query input for evaluating consent.
     *
     * @param subject the data subject identifier
     * @param scopes the set of consent scopes to evaluate
     */
    public record EvaluateConsentQuery(String subject, Set<ConsentScope> scopes) {}

    @Override
    public ConsentResponse execute(EvaluateConsentQuery query) {
        return consentService.evaluateConsent(query.subject(), query.scopes());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
