package com.koostack.cveil.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.sdk.model.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CveilClient — high-level facade over the cveil-sdk-core WASM module.
 *
 * <p>Handles:
 *
 * <ul>
 *   <li>WASM module loading and initialization
 *   <li>Paginated graph ingestion from the cveil REST API
 *   <li>SSE connection for real-time cache invalidation
 *   <li>Automatic reconnection with exponential backoff
 *   <li>Consent decision fetching on cache miss
 * </ul>
 *
 * <p>Mirrors the JavaScript SDK's CveilClient API.
 *
 * @see <a href="../sdks/cveil-sdk-js/src/client.ts">JS SDK client.ts</a>
 */
public class CveilClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CveilClient.class.getName());

    private static final int DEFAULT_GRAPH_PAGE_SIZE = 100;
    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long RECONNECT_JITTER_MS = 500;
    private static final int SSE_BUFFER_MAX_BYTES = 1_048_576;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CveilConfig config;
    private final CveilClientOptions options;
    private final HttpClient httpClient;

    private WasmBridge bridge;
    private volatile boolean initialized = false;

    // SSE
    private final AtomicBoolean sseRunning = new AtomicBoolean(false);
    private final AtomicLong reconnectDelay = new AtomicLong(RECONNECT_BASE_MS);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofVirtual().name("cveil-sse-scheduler").unstarted(r));
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Thread sseThread;

    private CveilClient(CveilClientOptions options) {
        this.config = options.config();
        this.options = options;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build();
    }

    /**
     * Package-private constructor for testing — injects a pre-loaded bridge and marks the client as
     * initialized, bypassing WASM loading and graph fetching.
     */
    CveilClient(CveilClientOptions options, WasmBridge bridge) {
        this(options);
        this.bridge = bridge;
        this.initialized = true;
    }

    /**
     * Create and initialize a CveilClient.
     *
     * <ol>
     *   <li>Loads the WASM module
     *   <li>Initializes the SDK with config
     *   <li>Fetches the full consent graph (paginated)
     *   <li>Connects to SSE for invalidation events (if autoConnect)
     * </ol>
     */
    public static CveilClient create(CveilClientOptions options) throws IOException {
        var client = new CveilClient(options);
        client.initialize();
        return client;
    }

    private void initialize() throws IOException {
        Path wasmPath = resolveWasmPath();
        this.bridge = WasmBridge.load(wasmPath);

        bridge.init(toJson(config));
        this.initialized = true;

        loadGraph();

        if (options.shouldAutoConnect()) {
            connectSSE();
        }
    }

    private Path resolveWasmPath() {
        if (options.wasmPath() != null) return options.wasmPath();
        return Path.of("wasm", "cveil_sdk_core.wasm");
    }

    // ─── Graph Loading ─────────────────────────────────────────────

    /** Fetch and ingest the full consent graph via paginated API calls. */
    public void loadGraph() throws IOException {
        int pageSize = options.effectiveGraphPageSize();
        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            var uriBuilder =
                    new StringBuilder(config.apiBaseUrl())
                            .append("/api/v1/consent/graph?limit=")
                            .append(pageSize);
            if (cursor != null) {
                uriBuilder.append("&cursor=").append(encode(cursor));
            }

            var response = httpGet(uriBuilder.toString());
            var page = mapper.readValue(response, ConsentGraphPage.class);
            var resultJson = bridge.ingestGraphPage(toJson(page));
            var result = mapper.readTree(resultJson);

            hasMore = result.path("hasMore").asBoolean(false);
            cursor = page.pageInfo() != null ? page.pageInfo().nextCursor() : null;
        }
    }

    // ─── Core Operations ──────────────────────────────────────────

    /**
     * Mask a JSON object based on consent graph evaluation. Auto-fetches missing decisions.
     *
     * @param subject The data subject identifier (e.g., user ID)
     * @param entity The entity type (e.g., "User")
     * @param data The JSON object to mask
     * @param purpose The processing purpose (e.g., "Marketing")
     * @return The masked data and any fields that need API fetching
     */
    public MaskResult mask(String subject, String entity, Map<String, Object> data, String purpose)
            throws IOException {
        ensureInitialized();
        var resultJson = bridge.mask(subject, entity, toJson(data), purpose);
        var result = mapper.readValue(resultJson, MaskResult.class);

        if (result.needsFetch() != null && !result.needsFetch().isEmpty()) {
            fetchAndIngestDecisions(subject, result.needsFetch());
            var retryJson = bridge.mask(subject, entity, toJson(data), purpose);
            return mapper.readValue(retryJson, MaskResult.class);
        }

        return result;
    }

    /** Mask with an explicit jurisdiction override. */
    public MaskResult maskWithJurisdiction(
            String subject,
            String entity,
            Map<String, Object> data,
            String purpose,
            String jurisdiction)
            throws IOException {
        ensureInitialized();
        var resultJson =
                bridge.maskWithJurisdiction(subject, entity, toJson(data), purpose, jurisdiction);
        var result = mapper.readValue(resultJson, MaskResult.class);

        if (result.needsFetch() != null && !result.needsFetch().isEmpty()) {
            fetchAndIngestDecisions(subject, result.needsFetch());
            var retryJson =
                    bridge.maskWithJurisdiction(
                            subject, entity, toJson(data), purpose, jurisdiction);
            return mapper.readValue(retryJson, MaskResult.class);
        }

        return result;
    }

    /** Mask an array of objects (same subject/entity/purpose). */
    public List<MaskResult> maskBatch(
            String subject, String entity, List<Map<String, Object>> data, String purpose)
            throws IOException {
        ensureInitialized();
        var resultJson = bridge.maskBatch(subject, entity, toJson(data), purpose);
        return mapper.readValue(resultJson, new TypeReference<>() {});
    }

    /** Evaluate consent for specific fields without masking. */
    public List<EvaluateResult> evaluate(
            String subject, String entity, List<String> fields, String purpose) throws IOException {
        ensureInitialized();
        var resultJson = bridge.evaluate(subject, entity, toJson(fields), purpose);
        return mapper.readValue(resultJson, new TypeReference<>() {});
    }

    /** Manually ingest consent decisions. */
    public void ingestDecisions(String subject, List<DecisionEntry> decisions) {
        ensureInitialized();
        bridge.ingestDecisions(subject, toJson(decisions));
    }

    /** Get SDK status. */
    public SdkStatus status() throws IOException {
        ensureInitialized();
        return mapper.readValue(bridge.status(), SdkStatus.class);
    }

    /** Graceful shutdown — flush caches and disconnect SSE. */
    public void shutdown() {
        disconnectSSE();
        if (initialized && bridge != null) {
            bridge.shutdown();
            initialized = false;
        }
        scheduler.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ─── Decision Fetching ────────────────────────────────────────

    private void fetchAndIngestDecisions(String subject, List<NeedsFetchEntry> needsFetch)
            throws IOException {
        var allPolicies = new LinkedHashSet<String>();
        for (var entry : needsFetch) {
            if (entry.policyNames() != null) {
                allPolicies.addAll(entry.policyNames());
            }
        }

        var scopes =
                allPolicies.stream().map(policyName -> Map.of("policyName", policyName)).toList();

        var uri = config.apiBaseUrl() + "/api/v1/subjects/" + encode(subject) + "/evaluate";
        try {
            var responseBody = httpPost(uri, Map.of("scopes", scopes));
            List<DecisionEntry> decisions =
                    mapper.readValue(responseBody, new TypeReference<>() {});
            bridge.ingestDecisions(subject, toJson(decisions));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[cveil] Failed to evaluate consent: {0}", e.getMessage());
        }
    }

    // ─── SSE Connection ───────────────────────────────────────────

    /** Connect to the SSE invalidation stream. */
    public void connectSSE() {
        disconnectSSE();
        sseRunning.set(true);
        reconnectDelay.set(RECONNECT_BASE_MS);
        doConnectSSE();
    }

    /** Disconnect from the SSE stream. */
    public void disconnectSSE() {
        sseRunning.set(false);
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        var thread = sseThread;
        if (thread != null) {
            thread.interrupt();
            sseThread = null;
        }
    }

    private void doConnectSSE() {
        if (!sseRunning.get()) return;

        sseThread =
                Thread.ofVirtual()
                        .name("cveil-sse")
                        .start(
                                () -> {
                                    try {
                                        var uri = config.apiBaseUrl() + "/api/v1/consent/events";
                                        var request =
                                                HttpRequest.newBuilder()
                                                        .uri(URI.create(uri))
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + config.apiToken())
                                                        .header("Accept", "text/event-stream")
                                                        .timeout(Duration.ofHours(24))
                                                        .GET()
                                                        .build();

                                        var response =
                                                httpClient.send(
                                                        request,
                                                        HttpResponse.BodyHandlers.ofLines());

                                        if (response.statusCode() != 200) {
                                            throw new IOException(
                                                    "SSE connect failed: " + response.statusCode());
                                        }

                                        bridge.sseConnected();
                                        reconnectDelay.set(RECONNECT_BASE_MS);

                                        consumeSSEStream(response.body());
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    } catch (Exception e) {
                                        LOG.log(
                                                Level.WARNING,
                                                "[cveil] SSE connection error: {0}",
                                                e.getMessage());
                                    }
                                    handleSSEDisconnect();
                                });
    }

    private void consumeSSEStream(java.util.stream.Stream<String> lines) {
        var buffer = new StringBuilder();
        var iterator = lines.iterator();

        while (iterator.hasNext() && sseRunning.get()) {
            var line = iterator.next();

            if (buffer.length() > SSE_BUFFER_MAX_BYTES) {
                LOG.warning("[cveil] SSE buffer exceeded limit, aborting stream");
                break;
            }

            if (line.isEmpty()) {
                // End of event
                if (buffer.length() > 0) {
                    processSSEEvent(buffer.toString());
                    buffer.setLength(0);
                }
            } else {
                buffer.append(line).append('\n');
            }
        }
    }

    private void processSSEEvent(String raw) {
        String eventType = "";
        var dataLines = new ArrayList<String>();

        for (var line : raw.split("\n")) {
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).stripLeading());
            } else if (line.startsWith(":")) {
                continue; // comment/keep-alive
            }
        }

        var data = String.join("\n", dataLines);

        if ("cache-invalidation".equals(eventType) && !data.isEmpty()) {
            try {
                var resultJson = bridge.processInvalidation(data);
                var effect = mapper.readValue(resultJson, InvalidationEffect.class);

                if ("refresh_full_graph".equals(effect.effect())
                        || "flush_and_reload".equals(effect.effect())) {
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    loadGraph();
                                } catch (IOException e) {
                                    LOG.log(
                                            Level.WARNING,
                                            "[cveil] Failed to reload graph: {0}",
                                            e.getMessage());
                                }
                            });
                } else if ("refresh_subgraph".equals(effect.effect())
                        && effect.nodeType() != null
                        && effect.nodeName() != null) {
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    loadSubgraph(effect.nodeType(), effect.nodeName());
                                } catch (IOException e) {
                                    LOG.log(
                                            Level.WARNING,
                                            "[cveil] Failed to reload subgraph: {0}",
                                            e.getMessage());
                                }
                            });
                }
            } catch (Exception e) {
                LOG.log(
                        Level.WARNING,
                        "[cveil] Failed to process invalidation: {0}",
                        e.getMessage());
            }
        }
    }

    private void loadSubgraph(String rootType, String rootName) throws IOException {
        var uri =
                config.apiBaseUrl()
                        + "/api/v1/consent/graph?rootType="
                        + encode(rootType)
                        + "&rootName="
                        + encode(rootName)
                        + "&depth=3&limit=200";

        var response = httpGet(uri);
        var page = mapper.readValue(response, ConsentGraphPage.class);
        bridge.ingestGraphPage(toJson(page));
    }

    private void handleSSEDisconnect() {
        if (!sseRunning.get()) return;
        if (reconnectTask != null) return; // Prevent stacking timers

        bridge.sseDisconnected();

        long jitter = (long) (Math.random() * RECONNECT_JITTER_MS);
        long delay = reconnectDelay.get() + jitter;

        reconnectTask =
                scheduler.schedule(
                        () -> {
                            reconnectTask = null;
                            bridge.sseReconnected();
                            try {
                                loadGraph();
                            } catch (IOException e) {
                                LOG.log(
                                        Level.WARNING,
                                        "[cveil] Reconnect graph load failed: {0}",
                                        e.getMessage());
                                handleSSEDisconnect();
                                return;
                            }
                            doConnectSSE();
                        },
                        delay,
                        TimeUnit.MILLISECONDS);

        reconnectDelay.updateAndGet(d -> Math.min(d * 2, RECONNECT_MAX_MS));
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "CveilClient not initialized — call CveilClient.create()");
        }
    }

    // ─── HTTP Helper ──────────────────────────────────────────────

    private <T> T httpRequest(String method, String path, Object body, Class<T> responseType)
            throws IOException {
        ensureInitialized();
        var uri = URI.create(config.apiBaseUrl() + path);

        var builder =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "Bearer " + config.apiToken())
                        .header("Accept", "application/json");

        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(toJson(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                ApiErrorBody errorBody = null;
                try {
                    errorBody = mapper.readValue(response.body(), ApiErrorBody.class);
                } catch (Exception ignored) {
                    // response body wasn't JSON
                }
                throw new CveilApiError(response.statusCode(), errorBody, null);
            }

            var text = response.body();
            if (text == null || text.isBlank()) {
                return null;
            }
            return mapper.readValue(text, responseType);
        } catch (CveilApiError e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private <T> T httpRequest(
            String method, String path, Object body, TypeReference<T> responseType)
            throws IOException {
        ensureInitialized();
        var uri = URI.create(config.apiBaseUrl() + path);

        var builder =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "Bearer " + config.apiToken())
                        .header("Accept", "application/json");

        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(toJson(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                ApiErrorBody errorBody = null;
                try {
                    errorBody = mapper.readValue(response.body(), ApiErrorBody.class);
                } catch (Exception ignored) {
                }
                throw new CveilApiError(response.statusCode(), errorBody, null);
            }

            var text = response.body();
            if (text == null || text.isBlank()) {
                return null;
            }
            return mapper.readValue(text, responseType);
        } catch (CveilApiError e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    // ─── Consent Operations ───────────────────────────────────────

    /** Grant consent for a single scope. Optimistically updates the local decision cache. */
    public MessageResponse grant(GrantRequest request) throws IOException {
        var result =
                httpRequest(
                        "POST", "/api/v1/subjects/consent/grant", request, MessageResponse.class);
        optimisticIngest(request.subject(), request.scope(), "GRANT", request.expiresAt());
        return result;
    }

    /** Revoke consent for a single scope. Optimistically updates the local decision cache. */
    public MessageResponse revoke(RevokeRequest request) throws IOException {
        var result =
                httpRequest(
                        "POST", "/api/v1/subjects/consent/revoke", request, MessageResponse.class);
        optimisticIngest(request.subject(), request.scope(), "REVOKE", null);
        return result;
    }

    /** Batch grant consent (up to 1000 entries). */
    public BatchConsentResponse grantAll(BatchGrantRequest request) throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/subjects/consent/grant-all",
                        request,
                        BatchConsentResponse.class);
        var failedIndices = new HashSet<Integer>();
        if (result.errors() != null) {
            result.errors().forEach(e -> failedIndices.add(e.index()));
        }
        for (int i = 0; i < request.grants().size(); i++) {
            if (!failedIndices.contains(i)) {
                var entry = request.grants().get(i);
                optimisticIngest(request.subject(), entry.scope(), "GRANT", entry.expiresAt());
            }
        }
        return result;
    }

    /** Batch revoke consent (up to 1000 entries). */
    public BatchConsentResponse revokeAll(BatchRevokeRequest request) throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/subjects/consent/revoke-all",
                        request,
                        BatchConsentResponse.class);
        var failedIndices = new HashSet<Integer>();
        if (result.errors() != null) {
            result.errors().forEach(e -> failedIndices.add(e.index()));
        }
        for (int i = 0; i < request.revokes().size(); i++) {
            if (!failedIndices.contains(i)) {
                optimisticIngest(
                        request.subject(), request.revokes().get(i).scope(), "REVOKE", null);
            }
        }
        return result;
    }

    /** Evaluate consent for a set of scopes against the backend. */
    public EvaluateConsentResponse evaluateConsent(String subject, EvaluateConsentRequest request)
            throws IOException {
        return httpRequest(
                "POST",
                "/api/v1/subjects/" + encode(subject) + "/evaluate",
                request,
                EvaluateConsentResponse.class);
    }

    /** Get a subject's consent summary. */
    public SubjectConsentView getSubjectSummary(String subject) throws IOException {
        return httpRequest(
                "GET",
                "/api/v1/subjects/" + encode(subject) + "/summary",
                null,
                SubjectConsentView.class);
    }

    /** Get a subject's consent history. */
    public List<ConsentLedgerEntry> getSubjectHistory(String subject) throws IOException {
        return httpRequest(
                "GET",
                "/api/v1/subjects/" + encode(subject) + "/history",
                null,
                new TypeReference<>() {});
    }

    private void optimisticIngest(
            String subject, ConsentScope scope, String action, String expiresAt) {
        if (bridge == null) return;
        var policyName = scopeToPolicyName(scope);
        if (policyName == null) return;

        Long expiresAtMs = null;
        if (expiresAt != null) {
            expiresAtMs = Instant.parse(expiresAt).toEpochMilli();
        }

        var decisions = List.of(new DecisionEntry(policyName, action, expiresAtMs));
        bridge.ingestDecisions(subject, toJson(decisions));
    }

    private String scopeToPolicyName(ConsentScope scope) {
        if (scope.fieldName() != null
                && scope.entityName() != null
                && scope.purposeName() != null) {
            return "FIELD:"
                    + scope.entityName()
                    + "/"
                    + scope.fieldName()
                    + ":"
                    + scope.purposeName();
        }
        if (scope.category() != null && scope.purposeName() != null) {
            return "CAT:" + scope.category() + ":" + scope.purposeName();
        }
        return null;
    }

    // ─── Subject Operations ───────────────────────────────────────

    /** List all registered subjects. */
    public List<SubjectView> listSubjects() throws IOException {
        return httpRequest("GET", "/api/v1/subjects", null, new TypeReference<>() {});
    }

    /** Get a single subject by name. */
    public SubjectView getSubject(String name) throws IOException {
        return httpRequest("GET", "/api/v1/subjects/" + encode(name), null, SubjectView.class);
    }

    /** Register a new subject. */
    public MessageResponse registerSubject(RegisterSubjectRequest request) throws IOException {
        return httpRequest("POST", "/api/v1/subjects", request, MessageResponse.class);
    }

    /** Update an existing subject. */
    public MessageResponse updateSubject(String name, UpdateSubjectRequest request)
            throws IOException {
        return httpRequest(
                "PUT", "/api/v1/subjects/" + encode(name), request, MessageResponse.class);
    }

    /** Remove a subject. */
    public MessageResponse removeSubject(String name) throws IOException {
        return httpRequest(
                "DELETE", "/api/v1/subjects/" + encode(name), null, MessageResponse.class);
    }

    /** GDPR Article 20 — export all data for a subject. */
    public SubjectDataExport exportSubjectData(String subject) throws IOException {
        return httpRequest(
                "GET",
                "/api/v1/subjects/" + encode(subject) + "/export",
                null,
                SubjectDataExport.class);
    }

    /** GDPR Article 17 — request deletion of all subject data. */
    public DsarDeletionReceipt deleteSubjectData(String subject) throws IOException {
        return httpRequest(
                "DELETE",
                "/api/v1/subjects/" + encode(subject) + "/audit?confirm=DELETE_" + encode(subject),
                null,
                DsarDeletionReceipt.class);
    }

    /** Shred the DSAR encryption key for a subject (irreversible). */
    public MessageResponse shredDsarKey(String name) throws IOException {
        return httpRequest(
                "DELETE",
                "/api/v1/subjects/" + encode(name) + "/dsar-key",
                null,
                MessageResponse.class);
    }

    // ─── Governance: Purposes ─────────────────────────────────────

    public List<PurposeView> listPurposes() throws IOException {
        return httpRequest("GET", "/api/v1/governance/purposes", null, new TypeReference<>() {});
    }

    public PurposeView getPurpose(String name) throws IOException {
        return httpRequest(
                "GET", "/api/v1/governance/purposes/" + encode(name), null, PurposeView.class);
    }

    public MessageResponse registerPurpose(RegisterPurposeRequest request) throws IOException {
        var result =
                httpRequest("POST", "/api/v1/governance/purposes", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removePurpose(String name) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/purposes/" + encode(name),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Governance: Purpose Policies ─────────────────────────────

    public MessageResponse addPurposePolicy(AddPurposePolicyRequest request) throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/governance/purposes/policies",
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removePurposePolicy(AddPurposePolicyRequest request) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/purposes/policies",
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Governance: Purpose ↔ Regulation Links ───────────────────

    public MessageResponse linkPurposeToRegulation(LinkPurposeRegulationRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/governance/purposes/regulations",
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse unlinkPurposeFromRegulation(String purposeName, String regulationName)
            throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/purposes/"
                                + encode(purposeName)
                                + "/regulations/"
                                + encode(regulationName),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Governance: Regulations ──────────────────────────────────

    public List<RegulationView> listRegulations() throws IOException {
        return httpRequest("GET", "/api/v1/governance/regulations", null, new TypeReference<>() {});
    }

    public RegulationView getRegulation(String name) throws IOException {
        return httpRequest(
                "GET",
                "/api/v1/governance/regulations/" + encode(name),
                null,
                RegulationView.class);
    }

    public MessageResponse registerRegulation(RegisterRegulationRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "POST", "/api/v1/governance/regulations", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removeRegulation(String name) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/regulations/" + encode(name),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Governance: Jurisdictions ────────────────────────────────

    public List<JurisdictionView> listJurisdictions() throws IOException {
        return httpRequest(
                "GET", "/api/v1/governance/jurisdictions", null, new TypeReference<>() {});
    }

    public JurisdictionView getJurisdiction(String name) throws IOException {
        return httpRequest(
                "GET",
                "/api/v1/governance/jurisdictions/" + encode(name),
                null,
                JurisdictionView.class);
    }

    public MessageResponse registerJurisdiction(RegisterJurisdictionRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "POST", "/api/v1/governance/jurisdictions", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse updateJurisdiction(String name, UpdateJurisdictionRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "PUT",
                        "/api/v1/governance/jurisdictions/" + encode(name),
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removeJurisdiction(String name) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/jurisdictions/" + encode(name),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Governance: Regulation ↔ Jurisdiction Links ──────────────

    public MessageResponse addJurisdictionToRegulation(AddJurisdictionToRegulationRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/governance/regulations/jurisdictions",
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removeJurisdictionFromRegulation(
            String regulationName, String jurisdictionName) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/governance/regulations/"
                                + encode(regulationName)
                                + "/jurisdictions/"
                                + encode(jurisdictionName),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Catalogue: Fields ────────────────────────────────────────

    public List<FieldView> listFields() throws IOException {
        return httpRequest("GET", "/api/v1/catalogue/fields", null, new TypeReference<>() {});
    }

    public MessageResponse registerField(RegisterFieldRequest request) throws IOException {
        var result =
                httpRequest("POST", "/api/v1/catalogue/fields", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse removeField(String entityName, String fieldName) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/catalogue/fields/" + encode(entityName) + "/" + encode(fieldName),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Catalogue: Entities ──────────────────────────────────────

    public List<EntityView> listEntities() throws IOException {
        return httpRequest("GET", "/api/v1/catalogue/entities", null, new TypeReference<>() {});
    }

    public EntityView getEntity(String name) throws IOException {
        return httpRequest(
                "GET", "/api/v1/catalogue/entities/" + encode(name), null, EntityView.class);
    }

    public MessageResponse registerEntity(RegisterEntityRequest request) throws IOException {
        var result =
                httpRequest("POST", "/api/v1/catalogue/entities", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse updateEntity(String name, UpdateEntityRequest request)
            throws IOException {
        return httpRequest(
                "PUT",
                "/api/v1/catalogue/entities/" + encode(name),
                request,
                MessageResponse.class);
    }

    public MessageResponse removeEntity(String name) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/catalogue/entities/" + encode(name),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Catalogue: Categories ────────────────────────────────────

    public List<CategoryView> listCategories() throws IOException {
        return httpRequest("GET", "/api/v1/catalogue/categories", null, new TypeReference<>() {});
    }

    public CategoryView getCategory(String name) throws IOException {
        return httpRequest(
                "GET", "/api/v1/catalogue/categories/" + encode(name), null, CategoryView.class);
    }

    public MessageResponse registerCategory(RegisterCategoryRequest request) throws IOException {
        var result =
                httpRequest("POST", "/api/v1/catalogue/categories", request, MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse updateCategory(String name, UpdateCategoryRequest request)
            throws IOException {
        return httpRequest(
                "PUT",
                "/api/v1/catalogue/categories/" + encode(name),
                request,
                MessageResponse.class);
    }

    public MessageResponse removeCategory(String name) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/catalogue/categories/" + encode(name),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Catalogue: Field ↔ Category Links ────────────────────────

    public MessageResponse assignFieldToCategory(AssignFieldCategoryRequest request)
            throws IOException {
        var result =
                httpRequest(
                        "POST",
                        "/api/v1/catalogue/fields/categories",
                        request,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    public MessageResponse unassignFieldFromCategory(
            String entityName, String fieldName, String categoryName) throws IOException {
        var result =
                httpRequest(
                        "DELETE",
                        "/api/v1/catalogue/fields/"
                                + encode(entityName)
                                + "/"
                                + encode(fieldName)
                                + "/categories/"
                                + encode(categoryName),
                        null,
                        MessageResponse.class);
        reloadGraphAfterMutation();
        return result;
    }

    // ─── Graph Reload Helper ──────────────────────────────────────

    private void reloadGraphAfterMutation() {
        if (bridge == null) return;
        try {
            loadGraph();
        } catch (IOException e) {
            LOG.log(
                    Level.WARNING,
                    "[cveil] Graph reload after mutation failed: {0}",
                    e.getMessage());
        }
    }

    // ─── Internal HTTP Helpers ────────────────────────────────────

    private String httpGet(String uri) throws IOException {
        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .header("Authorization", "Bearer " + config.apiToken())
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private String httpPost(String uri, Object body) throws IOException {
        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .header("Authorization", "Bearer " + config.apiToken())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                        .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new CveilWasmError("JSON serialization failed", e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
