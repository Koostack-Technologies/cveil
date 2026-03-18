package com.koostack.cveil.rest.gate;

import com.koostack.cveil.core.platform.plan.FeatureGateService;
import com.koostack.cveil.core.platform.plan.PlanFeature;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI-managed feature gate checker backed by the {@code platform_core.plan_feature} table.
 *
 * <p>Caches feature flags per plan for 5 minutes to avoid per-request DB queries. Plan features
 * change only on plan updates or migrations, so aggressive caching is safe.
 */
public class FeatureGateChecker implements FeatureGateService {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureGateChecker.class);

    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private static final org.jooq.Table<?> PLAN_FEATURE =
            DSL.table(DSL.name("cveilcore", "plan_feature"));
    private static final org.jooq.Field<String> F_PLAN_ID =
            DSL.field(DSL.name("plan_id"), String.class);
    private static final org.jooq.Field<String> F_FEATURE_KEY =
            DSL.field(DSL.name("feature_key"), String.class);
    private static final org.jooq.Field<Boolean> F_ENABLED =
            DSL.field(DSL.name("enabled"), Boolean.class);
    private static final org.jooq.Field<Integer> F_LIMIT_VALUE =
            DSL.field(DSL.name("limit_value"), Integer.class);

    private final DSLContext dsl;
    private final Map<String, CachedFeatures> cache = new ConcurrentHashMap<>();

    public FeatureGateChecker(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isFeatureEnabled(String planId, String featureKey) {
        return getFeature(planId, featureKey).map(PlanFeature::enabled).orElse(false);
    }

    @Override
    public int getFeatureLimit(String planId, String featureKey) {
        return getFeature(planId, featureKey)
                .map(f -> f.enabled() ? (f.limitValue() != null ? f.limitValue() : -1) : 0)
                .orElse(0);
    }

    @Override
    public Optional<PlanFeature> getFeature(String planId, String featureKey) {
        return getFeaturesForPlan(planId).stream()
                .filter(f -> f.featureKey().equals(featureKey))
                .findFirst();
    }

    @Override
    public List<PlanFeature> getFeaturesForPlan(String planId) {
        if (planId == null) return Collections.emptyList();

        CachedFeatures cached = cache.get(planId);
        if (cached != null && !cached.isExpired()) {
            return cached.features;
        }

        try {
            List<PlanFeature> features =
                    dsl.select()
                            .from(PLAN_FEATURE)
                            .where(F_PLAN_ID.eq(planId))
                            .fetch(this::toFeature);

            cache.put(planId, new CachedFeatures(features, System.currentTimeMillis()));
            return features;
        } catch (Exception e) {
            LOG.warn("Failed to load features for plan '{}': {}", planId, e.getMessage());
            return cached != null ? cached.features : Collections.emptyList();
        }
    }

    /** Evict cached features for a plan (call after plan updates). */
    public void evict(String planId) {
        cache.remove(planId);
    }

    /** Evict all cached features. */
    public void evictAll() {
        cache.clear();
    }

    private PlanFeature toFeature(Record record) {
        return new PlanFeature(
                record.get(F_PLAN_ID),
                record.get(F_FEATURE_KEY),
                Boolean.TRUE.equals(record.get(F_ENABLED)),
                record.get(F_LIMIT_VALUE));
    }

    private record CachedFeatures(List<PlanFeature> features, long fetchedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
        }
    }
}
