package com.koostack.operio.bundle.embedded;

import com.koostack.operio.core.Operio;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.sync.embedded.EmbeddedConsentSyncServiceConfig;
import com.koostack.operio.plugin.consent.sync.embedded.EmbeddedConsentSyncServiceImpl;
import com.koostack.operio.plugin.consent.sync.embedded.ReconciliationResult;
import com.koostack.operio.plugin.consent.sync.embedded.ReconciliationStrategy;
import com.koostack.operio.plugin.discovery.sensitive.annotation.AnnotationDiscoveryConfig;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pre-composed bundle for the fully embedded consent stack.
 *
 * <p>Everything runs in-process with zero external infrastructure dependencies: the relational
 * admin schema is backed by an in-memory database (typically H2), and the consent decision cache is
 * backed by Caffeine. This makes the bundle ideal for development, testing, demos, and small
 * single-process production deployments.
 *
 * <p>Wires:
 *
 * <ul>
 *   <li>{@code EmbeddedConsentSyncServiceImpl} ("embedded-sync") — dual-write coordinator that
 *       writes via the command service and syncs to the Caffeine cache
 *   <li>{@code ConsentDecisionService} ("caffeine") — Caffeine-backed consent evaluation (O(1)
 *       in-memory lookup per scope)
 *   <li>{@code ConsentAdminCommandService} / {@code ConsentAdminQueryService} ("jooq") — standalone
 *       admin providers for schema registration (discovery pipeline)
 *   <li>{@code SensitiveDiscovery} ("sensitive-annotation-discovery") — annotation-based
 *       field/category discovery (optional — only wired when an {@link AnnotationDiscoveryConfig}
 *       is provided)
 * </ul>
 *
 * <h2>Instance Sharing</h2>
 *
 * <p>Because the Caffeine cache is in-process (unlike Redis, which is an external shared store),
 * the bundle must ensure that the sync service's internal {@code CaffeineConsentDecisionService}
 * and {@code JooqConsentAdminCommandService} instances are the <strong>same objects</strong> wired
 * into the builder as the consent decision and admin providers respectively. The bundle achieves
 * this by:
 *
 * <ol>
 *   <li>Creating the {@link EmbeddedConsentSyncServiceImpl} first and configuring it with the
 *       composite {@link EmbeddedConsentSyncServiceConfig}.
 *   <li>Extracting the sync service's internal {@code CaffeineConsentDecisionService} and {@code
 *       JooqConsentAdminCommandService} via the accessor methods {@link
 *       EmbeddedConsentSyncServiceImpl#caffeineDecisionService()} and {@link
 *       EmbeddedConsentSyncServiceImpl#commandService()}.
 *   <li>Wiring those <em>same instances</em> into the builder via the instance-accepting overloads
 *       {@link Operio.Builder#withConsentProvider(
 *       com.koostack.operio.core.service.ConsentDecisionService)} and the consent admin
 *       command/query provider setters.
 * </ol>
 *
 * <p>This guarantees that consent writes (via the sync service) populate the exact Caffeine cache
 * that consent reads (via the decision service) query, and that schema registration (via the
 * command service) targets the exact H2 database that the sync service writes consent data to.
 *
 * <p>When an {@code appName} is supplied it is set on the builder via {@link
 * Operio.Builder#withAppName(String)} so that diagnostic logging and any namespace-sensitive
 * artefacts are scoped correctly.
 *
 * <h2>Usage — minimal (zero-config)</h2>
 *
 * <pre>{@code
 * Operio operio = Operio.builder()
 *     .withBundle(new EmbeddedConsentBundle())
 *     .build();
 * }</pre>
 *
 * <h2>Usage — with annotation discovery</h2>
 *
 * <pre>{@code
 * Operio operio = Operio.builder()
 *     .withBundle(new EmbeddedConsentBundle(
 *         AnnotationDiscoveryConfig.of(User.class, Patient.class)
 *     ))
 *     .build();
 *
 * // Scan classes for @Sensitive, register fields/categories in H2
 * operio.syncDiscoveredFields();
 * }</pre>
 *
 * <h2>Usage — with custom configs and app name</h2>
 *
 * <pre>{@code
 * var jooqConfig = new JooqConsentAdminServiceConfig(
 *     "jdbc:h2:mem:my_app;DB_CLOSE_DELAY=-1", "H2"
 * );
 * var caffeineConfig = new CaffeineConsentDecisionServiceConfig(50_000);
 * var discoveryConfig = AnnotationDiscoveryConfig.of(
 *     User.class, Patient.class, Order.class
 * );
 *
 * Operio operio = Operio.builder()
 *     .withBundle(new EmbeddedConsentBundle(
 *         "my-healthcare-app", jooqConfig, caffeineConfig, discoveryConfig))
 *     .build();
 *
 * operio.syncDiscoveredFields();
 *
 * operio.grantConsent("user-123",
 *     ConsentScope.to("PII", "email", "User", "marketing"),
 *     Instant.now().plus(Duration.ofDays(365)));
 * }</pre>
 *
 * @see EmbeddedConsentSyncServiceImpl
 * @see com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService
 * @see com.koostack.operio.plugin.consent.jooq.JooqConsentAdminCommandService
 */
public class EmbeddedConsentBundle implements PluginBundle {

    private static final Logger log = Logger.getLogger(EmbeddedConsentBundle.class.getName());

    /** Default H2 in-memory JDBC URL used when no jOOQ config is provided. */
    static final String DEFAULT_JDBC_URL = "jdbc:h2:mem:operio;DB_CLOSE_DELAY=-1";

    /** Default H2 dialect string. */
    static final String DEFAULT_DIALECT = "H2";

    private final String appName;
    private final JooqConsentAdminServiceConfig jooqConfig;
    private final CaffeineConsentDecisionServiceConfig caffeineConfig;
    private final AnnotationDiscoveryConfig discoveryConfig;
    private final EventService<?> eventService;
    private boolean autoRegisterMetadata = true;
    private ReconciliationStrategy reconcileOnStartup;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates a fully embedded bundle with all default settings and no annotation discovery.
     *
     * <p>Uses an in-memory H2 database with the default JDBC URL ({@value #DEFAULT_JDBC_URL}) and a
     * Caffeine cache with the default maximum size. No application name is explicitly set (defaults
     * to {@code "operio"}).
     */
    public EmbeddedConsentBundle() {
        this(null, null, null, null);
    }

    /**
     * Creates a fully embedded bundle with annotation discovery and all other settings defaulted.
     *
     * @param discoveryConfig configuration for annotation discovery, or {@code null} to skip
     *     discovery wiring
     */
    public EmbeddedConsentBundle(AnnotationDiscoveryConfig discoveryConfig) {
        this(null, null, null, discoveryConfig);
    }

    /**
     * Creates a fully embedded bundle with custom jOOQ and Caffeine configs but no annotation
     * discovery and no explicit application name.
     *
     * @param jooqConfig configuration for the jOOQ-backed admin service, or {@code null} to use the
     *     default H2 in-memory settings
     * @param caffeineConfig configuration for the Caffeine-backed decision service, or {@code null}
     *     to use the default cache settings
     */
    public EmbeddedConsentBundle(
            JooqConsentAdminServiceConfig jooqConfig,
            CaffeineConsentDecisionServiceConfig caffeineConfig) {
        this(null, jooqConfig, caffeineConfig, null);
    }

    /**
     * Creates a fully embedded bundle with an explicit application name and annotation discovery,
     * using default jOOQ and Caffeine settings.
     *
     * @param appName the application name used for diagnostic logging and namespace-sensitive
     *     artefacts, or {@code null} to use the default
     * @param discoveryConfig configuration for annotation discovery, or {@code null} to skip
     *     discovery wiring
     */
    public EmbeddedConsentBundle(String appName, AnnotationDiscoveryConfig discoveryConfig) {
        this(appName, null, null, discoveryConfig);
    }

    /**
     * Creates a fully embedded bundle with an explicit application name and without annotation
     * discovery.
     *
     * @param appName the application name, or {@code null} to use the default
     * @param jooqConfig configuration for the jOOQ-backed admin service, or {@code null} to use
     *     defaults
     * @param caffeineConfig configuration for the Caffeine-backed decision service, or {@code null}
     *     to use defaults
     */
    public EmbeddedConsentBundle(
            String appName,
            JooqConsentAdminServiceConfig jooqConfig,
            CaffeineConsentDecisionServiceConfig caffeineConfig) {
        this(appName, jooqConfig, caffeineConfig, null);
    }

    /**
     * Creates a fully embedded bundle with all options.
     *
     * <p>This is the canonical constructor — all other constructors delegate here.
     *
     * <p>When {@code appName} is non-null it is set on the {@link Operio.Builder} so that
     * diagnostic logging and any namespace-sensitive artefacts are scoped under the given name.
     *
     * <p>When {@code jooqConfig} is {@code null}, a default H2 in-memory config ({@value
     * #DEFAULT_JDBC_URL}, dialect {@value #DEFAULT_DIALECT}) is used. When {@code caffeineConfig}
     * is {@code null}, a default Caffeine config with {@link
     * CaffeineConsentDecisionServiceConfig#DEFAULT_MAX_SIZE} entries is used.
     *
     * <p>When {@code discoveryConfig} is non-null, the {@code SensitiveAnnotationDiscovery} plugin
     * is wired so that {@link Operio#syncDiscoveredFields()} can scan the specified classes for
     * {@code @Sensitive} annotations and automatically register the discovered fields and
     * categories into the consent admin schema.
     *
     * @param appName the application name, or {@code null} to use the default
     * @param jooqConfig configuration for the jOOQ-backed admin service, or {@code null} for
     *     default H2 in-memory settings
     * @param caffeineConfig configuration for the Caffeine-backed decision service, or {@code null}
     *     for default cache settings
     * @param discoveryConfig configuration for annotation discovery, or {@code null} to skip
     *     discovery wiring
     */
    public EmbeddedConsentBundle(
            String appName,
            JooqConsentAdminServiceConfig jooqConfig,
            CaffeineConsentDecisionServiceConfig caffeineConfig,
            AnnotationDiscoveryConfig discoveryConfig) {
        this(appName, jooqConfig, caffeineConfig, discoveryConfig, null);
    }

    /**
     * Creates a fully embedded bundle with all options including an event service for the CQRS
     * command pipeline.
     *
     * @param appName the application name, or {@code null}
     * @param jooqConfig configuration for the jOOQ-backed admin service, or {@code null} for
     *     default H2
     * @param caffeineConfig configuration for the Caffeine-backed decision service, or {@code null}
     *     for defaults
     * @param discoveryConfig configuration for annotation discovery, or {@code null} to skip
     *     discovery wiring
     * @param eventService the event service for the command pipeline, or {@code null} for
     *     cache-only mode
     */
    public EmbeddedConsentBundle(
            String appName,
            JooqConsentAdminServiceConfig jooqConfig,
            CaffeineConsentDecisionServiceConfig caffeineConfig,
            AnnotationDiscoveryConfig discoveryConfig,
            EventService<?> eventService) {
        this.appName = appName;
        this.jooqConfig = jooqConfig;
        this.caffeineConfig = caffeineConfig;
        this.discoveryConfig = discoveryConfig;
        this.eventService = eventService;
    }

    // -----------------------------------------------------------------------
    // PluginBundle
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Fluent configuration
    // -----------------------------------------------------------------------

    /**
     * Controls whether the sync service should auto-register entities, fields, categories, and
     * field↔category links before every grant/revoke DB write.
     *
     * <p>When {@code true} (the default), the sync service calls {@code
     * ensureMetadataRegistered(scope)} before every write, idempotently upserting any referenced
     * metadata. This is a best-effort safety net so callers don't need to run a discovery pipeline
     * or manually register metadata before writing consent.
     *
     * <p>Set to {@code false} in environments where the consent admin schema must be strictly
     * managed and unexpected auto-creation of metadata is undesirable. When disabled, grant/revoke
     * calls will throw {@code IllegalArgumentException} from the admin layer if the referenced
     * metadata has not been previously registered (e.g. via {@code syncDiscoveredFields()} or
     * direct admin calls).
     *
     * @param autoRegisterMetadata {@code true} to enable auto-registration (default), {@code false}
     *     to disable
     * @return this bundle instance for method chaining
     */
    public EmbeddedConsentBundle withAutoRegisterMetadata(boolean autoRegisterMetadata) {
        this.autoRegisterMetadata = autoRegisterMetadata;
        return this;
    }

    /**
     * Enables automatic DB → Caffeine cache reconciliation during {@link #apply(Operio.Builder)},
     * using the default strategy ({@link ReconciliationStrategy#FULL_REBUILD}).
     *
     * <p>When enabled, the bundle calls {@link
     * EmbeddedConsentSyncServiceImpl#reconcileFromDb(ReconciliationStrategy)} after all plugins are
     * wired, rehydrating the Caffeine cache from the relational database. This ensures the first
     * consent evaluation does not return all-denied because the in-memory cache is empty.
     *
     * <h3>Trade-offs</h3>
     *
     * <ul>
     *   <li>{@link ReconciliationStrategy#FULL_REBUILD} — guarantees an exact DB mirror but
     *       introduces a brief read gap (the cache is cleared before repopulation). In practice,
     *       this gap is invisible at startup because no reads are in flight yet.
     *   <li>{@link ReconciliationStrategy#MERGE} — writes DB state without clearing. No read gap
     *       but stale entries may linger. Useful if the bundle is re-applied at runtime (unusual).
     * </ul>
     *
     * <p>If the DB is empty (no consent policies or field-category mappings), reconciliation
     * completes instantly with zero counts — there is no performance penalty for enabling it on a
     * fresh install.
     *
     * @return this bundle instance for method chaining
     * @see #withReconcileOnStartup(ReconciliationStrategy)
     */
    public EmbeddedConsentBundle withReconcileOnStartup() {
        this.reconcileOnStartup = ReconciliationStrategy.FULL_REBUILD;
        return this;
    }

    /**
     * Enables automatic DB → Caffeine cache reconciliation during {@link #apply(Operio.Builder)},
     * using the specified strategy.
     *
     * @param strategy the reconciliation strategy to use at startup; pass {@code null} to disable
     *     startup reconciliation
     * @return this bundle instance for method chaining
     * @see #withReconcileOnStartup()
     * @see ReconciliationStrategy
     */
    public EmbeddedConsentBundle withReconcileOnStartup(ReconciliationStrategy strategy) {
        this.reconcileOnStartup = strategy;
        return this;
    }

    // -----------------------------------------------------------------------
    // PluginBundle
    // -----------------------------------------------------------------------

    @Override
    public String name() {
        return "embedded";
    }

    /**
     * Applies this bundle's plugins to the given builder, sharing service instances across the
     * sync, decision, and admin provider slots.
     *
     * <p>The wiring order is:
     *
     * <ol>
     *   <li>Set the application name (if provided).
     *   <li>Create and configure the {@link EmbeddedConsentSyncServiceImpl} with the composite
     *       config — this internally creates the query service, command service, and {@code
     *       CaffeineConsentDecisionService}.
     *   <li>Extract the sync service's internal decision service and wire it as the consent
     *       provider (handles reads — same Caffeine cache the sync writes to).
     *   <li>Optionally wire annotation-based discovery.
     * </ol>
     *
     * @param builder the Operio builder to configure
     */
    @Override
    public void apply(Operio.Builder builder) {
        // Application-level namespace
        if (appName != null) {
            builder.withAppName(appName);
        }

        // Resolve effective configs (use defaults for any null values)
        JooqConsentAdminServiceConfig effectiveJooq =
                jooqConfig != null
                        ? jooqConfig
                        : new JooqConsentAdminServiceConfig(DEFAULT_JDBC_URL, DEFAULT_DIALECT);

        CaffeineConsentDecisionServiceConfig effectiveCaffeine =
                caffeineConfig != null
                        ? caffeineConfig
                        : new CaffeineConsentDecisionServiceConfig();

        // Create the sync service — this internally creates the query,
        // command, and Caffeine decision service instances.
        EmbeddedConsentSyncServiceImpl syncService = new EmbeddedConsentSyncServiceImpl();
        syncService.configure(
                new EmbeddedConsentSyncServiceConfig(
                        effectiveJooq, effectiveCaffeine, autoRegisterMetadata, eventService));

        // Read-path: Caffeine evaluation (O(1) in-memory lookups).
        // Uses the SAME CaffeineConsentDecisionService instance that the
        // sync service populates — this is critical because the Caffeine
        // cache is in-process and cannot be shared between separate
        // instances (unlike Redis).
        builder.withConsentProvider(syncService.caffeineDecisionService());

        // Optional: annotation-based discovery
        if (discoveryConfig != null) {
            builder.withSensitiveDiscovery("sensitive-annotation-discovery", discoveryConfig);
        }

        // Optional: reconcile Caffeine cache from DB at startup
        if (reconcileOnStartup != null) {
            try {
                ReconciliationResult result = syncService.reconcileFromDb(reconcileOnStartup);
                log.log(Level.INFO, "Startup reconciliation complete: {0}", result.summary());
            } catch (Exception e) {
                log.log(
                        Level.WARNING,
                        "Startup reconciliation failed — cache may be cold "
                                + "until the next successful reconciliation or "
                                + "sync write: {0}",
                        e.getMessage());
            }
        }
    }
}
