package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisContextFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import redis.clients.jedis.JedisPooled;

/**
 * Redis-backed {@link ConsentDecisionService} implementation.
 *
 * <p>Delegates all Redis interaction to {@link ConsentPolicyRedisRepository},
 * which stores consent decisions in a Hash-per-subject schema with reverse
 * indices. Evaluation uses a 2-step precedence model: field-level checked
 * first, then category-level.
 *
 * <h2>Evaluation Strategy</h2>
 * <p>All consent scopes are evaluated via a single {@code HMGET} with 2N
 * hash fields (one field-level + one category-level check per scope).
 * Field-level takes precedence over category-level.
 *
 * @see ConsentPolicyRedisRepository
 * @see ConsentPolicyRedisRepository#evaluateScopes(String, Set)
 */
public class RedisConsentDecisionService
    implements ConsentDecisionService<RedisConsentDecisionServiceConfig>
{

    /** Reason message for scopes denied by an explicit REVOKED entry. */
    static final String REASON_REVOKED = "Consent explicitly revoked";

    /** Reason message for scopes denied because no consent record exists. */
    static final String REASON_NO_RECORD = "No consent record found";

    private JedisProvider jedisProvider;
    private ConsentPolicyRedisRepository consentPolicyRepository;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "redis";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public Class<RedisConsentDecisionServiceConfig> getConfigurationType() {
        return RedisConsentDecisionServiceConfig.class;
    }

    @Override
    public void configure(RedisConsentDecisionServiceConfig config) {
        JedisPooled client = RedisContextFactory.createClient(config);
        configure(JedisProvider.shared(client));
    }

    /**
     * Configures this service with a {@link JedisProvider} for per-tenant
     * Redis connection resolution.
     *
     * @param provider the Jedis provider — must not be {@code null}
     */
    public void configure(JedisProvider provider) {
        if (provider == null) {
            throw new NullPointerException("JedisProvider must not be null");
        }
        this.jedisProvider = provider;
        this.consentPolicyRepository = new ConsentPolicyRedisRepository(provider);
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // ConsentDecisionService
    // -----------------------------------------------------------------------

    @Override
    public ConsentResponse evaluateConsent(
        String subject,
        Set<ConsentScope> accessRequests
    ) {
        ensureConfigured();

        if (accessRequests == null || accessRequests.isEmpty()) {
            return ConsentResponse.to(
                subject,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap()
            );
        }

        Map<ConsentScope, DecisionDetail> details =
            consentPolicyRepository.evaluateScopes(subject, accessRequests);

        Set<ConsentScope> granted = new HashSet<>();
        Set<ConsentScope> denied = new HashSet<>();
        Map<String, String> reasons = new HashMap<>();

        for (ConsentScope scope : accessRequests) {
            DecisionDetail detail = details.get(scope);

            if (
                detail != null &&
                ConsentPolicyRedisRepository.GRANTED.equals(
                    detail.effectiveStatus()
                )
            ) {
                granted.add(scope);
            } else {
                denied.add(scope);
                reasons.put(scope.fieldName(), buildReasonMessage(detail));
            }
        }

        return ConsentResponse.to(subject, granted, denied, reasons, details);
    }

    // -----------------------------------------------------------------------
    // Accessors (post-configuration)
    // -----------------------------------------------------------------------

    public ConsentPolicyRedisRepository consentPolicyRepository() {
        ensureConfigured();
        return consentPolicyRepository;
    }

    public JedisProvider jedisProvider() {
        ensureConfigured();
        return jedisProvider;
    }

    public JedisPooled jedis() {
        ensureConfigured();
        return jedisProvider.get();
    }

    public long deleteAllCachedKeysForSubject(String subject) {
        ensureConfigured();
        return consentPolicyRepository.deleteAllCachedKeysForSubject(subject);
    }

    public long countCachedKeysForSubject(String subject) {
        ensureConfigured();
        return consentPolicyRepository.countCachedKeysForSubject(subject);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private static String buildReasonMessage(DecisionDetail detail) {
        if (detail == null) {
            return REASON_NO_RECORD;
        }
        String status = detail.effectiveStatus();
        if (status == null) {
            return REASON_NO_RECORD;
        }
        return REASON_REVOKED;
    }

    @Override
    public boolean isReady() {
        if (!configured || jedisProvider == null) {
            return false;
        }
        try {
            return "PONG".equals(jedisProvider.get().ping());
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureConfigured() {
        if (!configured) {
            throw new OperioConfigurationException(
                "RedisConsentDecisionService has not been configured. " +
                    "Call configure(RedisConsentDecisionServiceConfig) first."
            );
        }
    }
}
