package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.PurposeView;

/** Query actuator that looks up a policy by purpose, type, and field/category coordinates. */
public class FindPolicyQueryActuator
        implements QueryActuator<FindPolicyQueryActuator.FindPolicyQuery, PurposeView.PolicyView> {

    private final PolicyRepository policyRepository;

    public FindPolicyQueryActuator(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /**
     * Query input for finding a specific policy.
     *
     * @param purposeName the purpose name
     * @param policyType "FIELD" or "CATEGORY"
     * @param fieldName the field name (for FIELD policies)
     * @param entityName the entity name (for FIELD policies)
     * @param categoryName the category name (for CATEGORY policies)
     */
    public record FindPolicyQuery(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {}

    @Override
    public PurposeView.PolicyView execute(FindPolicyQuery query) {
        if ("FIELD".equals(query.policyType())) {
            return policyRepository
                    .findFieldPolicy(query.purposeName(), query.fieldName(), query.entityName())
                    .map(
                            r ->
                                    new PurposeView.PolicyView(
                                            r.get(POLICY.POLICY_ID),
                                            r.get(POLICY.POLICY_TYPE) != null
                                                    ? r.get(POLICY.POLICY_TYPE).toString()
                                                    : "FIELD",
                                            null,
                                            new PurposeView.FieldRef(
                                                    r.get(POLICY.FIELD_ID),
                                                    query.fieldName(),
                                                    query.entityName())))
                    .orElse(null);
        } else if ("CATEGORY".equals(query.policyType())) {
            return policyRepository
                    .findCategoryPolicy(query.purposeName(), query.categoryName())
                    .map(
                            r ->
                                    new PurposeView.PolicyView(
                                            r.get(POLICY.POLICY_ID),
                                            r.get(POLICY.POLICY_TYPE) != null
                                                    ? r.get(POLICY.POLICY_TYPE).toString()
                                                    : "CATEGORY",
                                            new PurposeView.CategoryRef(
                                                    r.get(POLICY.CATEGORY_ID),
                                                    query.categoryName()),
                                            null))
                    .orElse(null);
        }

        return null;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
