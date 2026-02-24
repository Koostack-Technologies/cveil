package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.Operio;
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
 * which stores each consent decision as a standalone Redis string key:
 * {@code operio:consent:{subject}:{fieldName}:{purposeName}} with value
 * {@code STATUS|SOURCE} (e.g. {@code GRANTED|FIELD}).
 *
 * <h2>Evaluation Strategy</h2>
 * <p>All consent policies — whether originally set at the field level or
 * materialized from a category — are pre-resolved into individual field
 * keys at write time. This means the read path is a single {@code MGET}
 * with zero resolution logic:
 * <ol>
 *   <li>Build one Redis key per requested {@link ConsentScope}.</li>
 *   <li>{@code MGET} all keys in one round trip.</li>
 *   <li>For each response: non-null with status {@code GRANTED} → granted;
 *       anything else → denied.</li>
 * </ol>
 *
 * <h2>Reason Messages</h2>
 * <p>Denied scopes are annotated with a human-readable reason in the
 * {@link ConsentResponse#reasons()} map, keyed by field name:
 * <ul>
 *   <li>{@code "Consent explicitly revoked"} — an explicit {@code REVOKED}
 *       entry exists for this field.</li>
 *   <li>{@code "No consent record found"} — no Redis key exists for this
 *       field (either never set or expired via TTL).</li>
 * </ul>
 *
 * <h2>Structured Audit Details</h2>
 * <p>Every evaluated scope — granted or denied — produces a
 * {@link DecisionDetail} in {@link ConsentResponse#details()}. The detail
 * captures the source ({@code FIELD} or {@code CATEGORY}) that produced
 * the entry, enabling rich audit trails. The {@code expiresAt} field is
 * {@code null} in Redis-sourced details — the relational
 * {@code consent_policy.expires_at} column is the source of truth for
 * expiration metadata.
 *
 * @see ConsentPolicyRedisRepository
 * @see ConsentPolicyRedisRepository#evaluateScopes(String, Set)
 */
public class RedisConsentDecisionService
    implements
        ConsentDecisionService<RedisConsentDecisionServiceConfig>,
        Operio.AppNameAware
{

    /** Reason message for scopes denied by an explicit REVOKED entry. */
    static final String REASON_REVOKED = "Consent explicitly revoked";

    /** Reason message for scopes denied because no consent record exists. */
    static final String REASON_NO_RECORD = "No consent record found";

    private JedisProvider jedisProvider;
    private ConsentPolicyRedisRepository consentPolicyRepository;
    private FieldCategoryMappingRedisRepository fieldCategoryMappingRepository;
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
     * <p>This is the recommended configuration path for multi-tenant
     * deployments. Pass a
     * {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider}
     * to enforce Redis-level ACL isolation per tenant — each tenant's
     * Redis operations will authenticate as that tenant's dedicated ACL
     * user, which is restricted to the tenant's key pattern by Redis
     * itself (server-side enforcement, not just application-level key
     * prefixing).
     *
     * @param provider the Jedis provider — must not be {@code null}
     */
    public void configure(JedisProvider provider) {
        if (provider == null) {
            throw new NullPointerException("JedisProvider must not be null");
        }
        this.jedisProvider = provider;
        this.consentPolicyRepository = new ConsentPolicyRedisRepository(
            provider
        );
        this.fieldCategoryMappingRepository =
            new FieldCategoryMappingRedisRepository(provider);
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // AppNameAware
    // -----------------------------------------------------------------------

    /**
     * Propagates the application name to the underlying Redis repositories
     * so that all keys are prefixed with the given name.
     *
     * @param appName the application name, never {@code null}
     */
    @Override
    public void setAppName(String appName) {
        if (consentPolicyRepository != null) {
            consentPolicyRepository.setAppName(appName);
        }
        if (fieldCategoryMappingRepository != null) {
            fieldCategoryMappingRepository.setAppName(appName);
        }
    }

    // -----------------------------------------------------------------------
    // ConsentDecisionService
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for the given subject against a batch of scopes.
     *
     * <p>This is the primary entry point used by the framework. It performs
     * exactly <strong>one Redis round trip</strong> ({@code MGET}) via
     * {@link ConsentPolicyRedisRepository#evaluateScopes(String, Set)}, then
     * partitions the results into granted/denied sets, builds human-readable
     * reason messages for every denial, and attaches structured
     * {@link DecisionDetail} records for all evaluated scopes.
     *
     * @param subject        the data subject identifier
     * @param accessRequests the set of scopes to evaluate
     * @return a fully populated {@link ConsentResponse}
     * @throws IllegalStateException if {@link #configure} has not been called
     */
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

        // Single MGET round trip — returns one DecisionDetail per scope
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

    /**
     * Returns the underlying consent-policy repository.
     *
     * <p>Primarily useful for test setup (seeding consent data) and for
     * advanced callers that need direct repository access.
     *
     * @return the consent policy repository
     * @throws IllegalStateException if not yet configured
     */
    public ConsentPolicyRedisRepository consentPolicyRepository() {
        ensureConfigured();
        return consentPolicyRepository;
    }

    /**
     * Returns the underlying field-category mapping repository.
     *
     * @return the field-category mapping repository
     * @throws IllegalStateException if not yet configured
     */
    public FieldCategoryMappingRedisRepository fieldCategoryMappingRepository() {
        ensureConfigured();
        return fieldCategoryMappingRepository;
    }

    /**
     * Returns the underlying {@link JedisProvider}.
     *
     * <p>Exposed so that callers can share the same provider (and its
     * per-tenant connection cache) with other components that need
     * tenant-scoped Redis access.
     *
     * @return the Jedis provider
     * @throws IllegalStateException if not yet configured
     */
    public JedisProvider jedisProvider() {
        ensureConfigured();
        return jedisProvider;
    }

    /**
     * Returns the underlying Jedis client for the current tenant context.
     *
     * <p>Exposed so that tests and administrative tooling can interact with
     * Redis directly when needed. In multi-tenant mode, this returns the
     * per-tenant connection (authenticated as the tenant's ACL user).
     *
     * @return the {@link JedisPooled} instance for the current context
     * @throws IllegalStateException if not yet configured
     */
    public JedisPooled jedis() {
        ensureConfigured();
        return jedisProvider.get();
    }

    /**
     * Deletes all cached consent keys for a subject (GDPR Article 17 erasure).
     *
     * <p>Discovers all Redis keys matching the pattern {@code operio:consent:{subject}:*}
     * and deletes them atomically. This is an idempotent operation — calling it
     * multiple times for the same subject is safe (subsequent calls are no-ops if
     * no keys exist).
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @return the number of keys deleted
     * @throws IllegalStateException if not yet configured
     */
    public long deleteAllCachedKeysForSubject(String subject) {
        ensureConfigured();
        return consentPolicyRepository.deleteAllCachedKeysForSubject(subject);
    }

    /**
     * Counts the cached consent keys for a subject without deleting them.
     *
     * <p>Discovers all Redis keys matching the pattern {@code operio:consent:{subject}:*}
     * and returns the count. This is useful for DSAR data export (GDPR Article 20 —
     * data portability).
     *
     * @param subject the data subject identifier (e.g. {@code "user_123"})
     * @return the number of cached keys for this subject
     * @throws IllegalStateException if not yet configured
     */
    public long countCachedKeysForSubject(String subject) {
        ensureConfigured();
        return consentPolicyRepository.countCachedKeysForSubject(subject);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Builds a human-readable denial reason from a {@link DecisionDetail}.
     *
     * @param detail the decision detail, or {@code null} if the scope was
     *               somehow missing from the evaluation map (defensive)
     * @return a reason string suitable for inclusion in
     *         {@link ConsentResponse#reasons()}
     */
    private static String buildReasonMessage(DecisionDetail detail) {
        if (detail == null) {
            return REASON_NO_RECORD;
        }
        String status = detail.effectiveStatus();
        if (status == null) {
            return REASON_NO_RECORD;
        }
        // Explicit revocation (REVOKED) at field or category level
        return REASON_REVOKED;
    }

    /**
     * Returns {@code true} if this plugin is configured and Redis is
     * reachable.
     *
     * <p>Performs a lightweight {@code PING} against the configured Redis
     * instance. Returns {@code false} if the service has not been configured
     * or if the check fails for any reason.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
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

    /**
     * Guards against use before {@link #configure} has been called.
     *
     * @throws OperioConfigurationException if the service is not yet configured
     */
    private void ensureConfigured() {
        if (!configured) {
            throw new OperioConfigurationException(
                "RedisConsentDecisionService has not been configured. " +
                    "Call configure(RedisConsentDecisionServiceConfig) first."
            );
        }
    }
}
