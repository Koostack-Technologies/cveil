package com.koostack.operio.plugin.consent.caffeine;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.service.ConsentDecisionService;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Caffeine-backed {@link ConsentDecisionService} implementation.
 *
 * <p>Delegates all storage to {@link CaffeineConsentRepository} (consent
 * entries with per-entry TTL) and {@link CaffeineFieldCategoryMappingRepository}
 * (bidirectional field↔category metadata). Everything lives in-process —
 * there are no external infrastructure dependencies.
 *
 * <h2>Evaluation Strategy</h2>
 * <p>All consent policies — whether originally set at the field level or
 * materialized from a category — are pre-resolved into individual field
 * entries at write time. This means the read path is a batch of O(1)
 * in-memory lookups with zero resolution logic:
 * <ol>
 *   <li>Build one cache key per requested {@link ConsentScope}.</li>
 *   <li>Look up all keys from the Caffeine cache.</li>
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
 *   <li>{@code "No consent record found"} — no cache entry exists for this
 *       field (either never set or expired via TTL).</li>
 * </ul>
 *
 * <h2>Structured Audit Details</h2>
 * <p>Every evaluated scope — granted or denied — produces a
 * {@link DecisionDetail} in {@link ConsentResponse#details()}. Unlike the
 * Redis implementation, the Caffeine variant also populates the
 * {@code expiresAt} field from the cached entry, enabling richer audit
 * trails without a separate relational lookup.
 *
 * <h2>Write API</h2>
 * <p>This service exposes the underlying {@link CaffeineConsentRepository}
 * and {@link CaffeineFieldCategoryMappingRepository} via accessor methods
 * so that the sync layer (e.g. {@code EmbeddedConsentSyncServiceImpl}) can
 * populate the cache on consent grant/revoke operations. The decision
 * service itself does not perform writes through the
 * {@link ConsentDecisionService} SPI — writes are coordinated by the sync
 * service or by callers that access the repositories directly.
 *
 * <h2>AppNameAware</h2>
 * <p>Implements {@link Operio.AppNameAware} for consistency with the Redis
 * implementation. In the Caffeine variant, the app name is stored but does
 * <em>not</em> affect cache key generation (keys are already process-scoped
 * and cannot collide with other JVM instances). The app name is available
 * via {@link #getAppName()} for logging and diagnostic purposes.
 *
 * @see CaffeineConsentRepository
 * @see CaffeineFieldCategoryMappingRepository
 * @see CaffeineConsentDecisionServiceConfig
 */
public class CaffeineConsentDecisionService
    implements
        ConsentDecisionService<CaffeineConsentDecisionServiceConfig>,
        Operio.AppNameAware
{

    /** Reason message for scopes denied by an explicit REVOKED entry. */
    static final String REASON_REVOKED = "Consent explicitly revoked";

    /** Reason message for scopes denied because no consent record exists. */
    static final String REASON_NO_RECORD = "No consent record found";

    /** Default application name (mirrors the Redis convention). */
    static final String DEFAULT_APP_NAME = "operio";

    private CaffeineConsentRepository consentRepository;
    private CaffeineFieldCategoryMappingRepository fieldCategoryMappingRepository;
    private String appName = DEFAULT_APP_NAME;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "caffeine";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<CaffeineConsentDecisionServiceConfig> getConfigurationType() {
        return CaffeineConsentDecisionServiceConfig.class;
    }

    @Override
    public void configure(CaffeineConsentDecisionServiceConfig config) {
        this.consentRepository = new CaffeineConsentRepository(config.maxSize());
        this.fieldCategoryMappingRepository =
            new CaffeineFieldCategoryMappingRepository();
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // AppNameAware
    // -----------------------------------------------------------------------

    /**
     * Stores the application name for diagnostic/logging purposes.
     *
     * <p>Unlike the Redis implementation, the app name does not affect
     * cache key generation — the Caffeine cache is process-scoped and
     * does not share state with other instances.
     *
     * @param appName the application name, never {@code null}
     */
    @Override
    public void setAppName(String appName) {
        this.appName = appName != null ? appName : DEFAULT_APP_NAME;
    }

    /**
     * Returns the current application name.
     *
     * @return the application name, never {@code null}
     */
    public String getAppName() {
        return appName;
    }

    // -----------------------------------------------------------------------
    // ConsentDecisionService
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for the given subject against a batch of scopes.
     *
     * <p>This is the primary entry point used by the framework. It performs
     * O(1) in-memory lookups per scope via
     * {@link CaffeineConsentRepository#evaluateScopes(String, Set)}, then
     * partitions the results into granted/denied sets, builds human-readable
     * reason messages for every denial, and attaches structured
     * {@link DecisionDetail} records for all evaluated scopes.
     *
     * @param subject        the data subject identifier
     * @param accessRequests the set of scopes to evaluate
     * @return a fully populated {@link ConsentResponse}
     * @throws OperioConfigurationException if {@link #configure} has not
     *                                      been called
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

        // In-memory batch lookup — no network round trip
        Map<ConsentScope, DecisionDetail> details =
            consentRepository.evaluateScopes(subject, accessRequests);

        Set<ConsentScope> granted = new HashSet<>();
        Set<ConsentScope> denied = new HashSet<>();
        Map<String, String> reasons = new HashMap<>();

        for (ConsentScope scope : accessRequests) {
            DecisionDetail detail = details.get(scope);

            if (
                detail != null &&
                CaffeineConsentRepository.GRANTED.equals(
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
     * Returns the underlying consent repository.
     *
     * <p>Primarily useful for the sync layer (populating the cache on
     * grant/revoke), for test setup (seeding consent data), and for
     * advanced callers that need direct repository access.
     *
     * @return the consent repository
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineConsentRepository consentRepository() {
        ensureConfigured();
        return consentRepository;
    }

    /**
     * Returns the underlying field-category mapping repository.
     *
     * <p>Used by the sync layer and by callers that need to manage
     * field↔category metadata directly.
     *
     * @return the field-category mapping repository
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineFieldCategoryMappingRepository fieldCategoryMappingRepository() {
        ensureConfigured();
        return fieldCategoryMappingRepository;
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if this plugin has been configured.
     *
     * <p>Since the Caffeine cache is entirely in-process, there is no
     * external infrastructure to check — the plugin is ready as soon as
     * {@link #configure} has been called.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
    @Override
    public boolean isReady() {
        return configured;
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
     * Guards against use before {@link #configure} has been called.
     *
     * @throws OperioConfigurationException if the service is not yet
     *                                      configured
     */
    private void ensureConfigured() {
        if (!configured) {
            throw new OperioConfigurationException(
                "CaffeineConsentDecisionService has not been configured. " +
                    "Call configure(CaffeineConsentDecisionServiceConfig) first."
            );
        }
    }
}
