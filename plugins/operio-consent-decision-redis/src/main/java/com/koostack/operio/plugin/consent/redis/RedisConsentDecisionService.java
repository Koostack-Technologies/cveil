package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.service.ConsentDecisionService;
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
 * which stores consent in a single hash per subject with prefixed keys for
 * field-level ({@code f:}) and category-level ({@code c:}) entries. Evaluation
 * uses a single {@code HGETALL} regardless of batch size — see the repository
 * Javadoc for full schema details.
 *
 * <h2>Evaluation Strategy (most-specific-wins)</h2>
 * <p>For each {@link ConsentScope} in the request:
 * <ol>
 *   <li>Check direct field-level consent ({@code f:{fieldName}:{purposeName}}).
 *       If a non-expired field-level entry exists ({@code GRANTED} or
 *       {@code REVOKED}), it is the effective status — field-level is the most
 *       specific and always takes precedence over category-level.</li>
 *   <li>Otherwise fall back to category-level consent
 *       ({@code c:{category}:{purposeName}}). If a non-expired category-level
 *       entry exists, it is the effective status.</li>
 *   <li>If neither level has a non-expired record, the scope is denied
 *       (effective status is {@code null}).</li>
 * </ol>
 *
 * <h2>Reason Messages</h2>
 * <p>Denied scopes are annotated with a human-readable reason in the
 * {@link ConsentResponse#reasons()} map, keyed by field name:
 * <ul>
 *   <li>{@code "Consent status is REVOKED"} — an explicit revocation was found
 *       at either the field or category level.</li>
 *   <li>{@code "No consent record found"} — no consent record exists at any
 *       level for the requested scope.</li>
 * </ul>
 *
 * <h2>Structured Audit Details</h2>
 * <p>Every evaluated scope — granted or denied — produces a
 * {@link DecisionDetail} in {@link ConsentResponse#details()}. The detail
 * captures the resolution level ({@code FIELD}, {@code CATEGORY}, or
 * {@code NONE}) and any associated expiration timestamp, enabling rich
 * audit trails and downstream analytics.
 *
 * @see ConsentPolicyRedisRepository
 * @see ConsentPolicyRedisRepository#evaluateScopes(String, Set)
 * @see ConsentPolicyRedisRepository#resolveScopes(Map, Set, java.time.Instant)
 */
public class RedisConsentDecisionService
    implements ConsentDecisionService<RedisConsentDecisionServiceConfig>
{

    /** Reason message for scopes denied by an explicit REVOKED entry. */
    static final String REASON_REVOKED = "Consent status is REVOKED";

    /** Reason message for scopes denied because no consent record exists. */
    static final String REASON_NO_RECORD = "No consent record found";

    private JedisPooled jedis;
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
        this.jedis = RedisContextFactory.createClient(config);
        this.consentPolicyRepository = new ConsentPolicyRedisRepository(jedis);
        this.fieldCategoryMappingRepository =
            new FieldCategoryMappingRedisRepository(jedis);
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // ConsentDecisionService
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for the given subject against a batch of scopes.
     *
     * <p>This is the primary entry point used by the framework. It performs
     * exactly <strong>one Redis round trip</strong> ({@code HGETALL}) via
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

        // Single Redis round trip — returns structured detail per scope
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
     * Returns the underlying Jedis client.
     *
     * <p>Exposed so that tests and administrative tooling can interact with
     * Redis directly when needed.
     *
     * @return the {@link JedisPooled} instance
     * @throws IllegalStateException if not yet configured
     */
    public JedisPooled jedis() {
        ensureConfigured();
        return jedis;
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Builds a human-readable denial reason from a {@link DecisionDetail}.
     *
     * @param detail the decision detail, or {@code null} if the scope was
     *               somehow missing from the resolution map (defensive)
     * @return a reason string suitable for inclusion in
     *         {@link ConsentResponse#reasons()}
     */
    private static String buildReasonMessage(DecisionDetail detail) {
        if (detail == null) {
            return REASON_NO_RECORD;
        }
        String status = detail.effectiveStatus();
        if (status == null) {
            // No consent record at any level
            return REASON_NO_RECORD;
        }
        // Explicit revocation (REVOKED) at field or category level
        return REASON_REVOKED;
    }

    /**
     * Guards against use before {@link #configure} has been called.
     *
     * @throws IllegalStateException if the service is not yet configured
     */
    private void ensureConfigured() {
        if (!configured) {
            throw new IllegalStateException(
                "RedisConsentDecisionService has not been configured. " +
                    "Call configure(RedisConsentDecisionServiceConfig) first."
            );
        }
    }
}
