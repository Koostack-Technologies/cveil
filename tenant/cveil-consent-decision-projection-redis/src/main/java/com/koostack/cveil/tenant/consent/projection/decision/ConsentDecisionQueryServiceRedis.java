package com.koostack.cveil.tenant.consent.projection.decision;

import com.koostack.cveil.core.exception.CveilConfigurationException;
import com.koostack.cveil.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.cveil.core.models.ConsentGraphModels.GraphEdge;
import com.koostack.cveil.core.models.ConsentGraphModels.GraphNode;
import com.koostack.cveil.core.models.ConsentGraphModels.GraphPageInfo;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.models.DecisionDetail;
import com.koostack.cveil.core.service.ConsentDecisionService;
import com.koostack.cveil.core.tenant.consent.ConsentGraphQueryService;
import com.koostack.cveil.core.util.FieldNameNormalizer;
import com.koostack.cveil.plugin.redis.common.ConsentGraphRedisConfig;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import com.koostack.cveil.plugin.redis.common.RedisContextFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph-based Redis implementation of consent evaluation and graph querying.
 *
 * <p>Implements both {@link ConsentDecisionService} (for consent evaluation via pipelined HGET
 * traversal) and {@link ConsentGraphQueryService} (for paginated graph queries with optional BFS
 * subgraph filtering).
 *
 * <h2>evaluateConsent</h2>
 *
 * <p>For each scope, issues 2 pipelined {@code HGET} calls on the edge property hashes: one for
 * field-level, one for category-level. Field-level takes precedence. All HGETs are batched into a
 * single Redis pipeline (one round trip).
 *
 * <h2>getConsentGraph</h2>
 *
 * <p>When no root is specified, paginates across all node types using {@code ZRANGEBYLEX} on sorted
 * set indexes. When a root is specified, performs BFS traversal to the given depth.
 */
public class ConsentDecisionQueryServiceRedis
        implements ConsentDecisionService<ConsentGraphRedisConfig>,
                ConsentGraphQueryService<ConsentGraphRedisConfig> {

    static final String REASON_REVOKED = "Consent explicitly revoked";
    static final String REASON_NO_RECORD = "No consent record found";

    private JedisProvider jedisProvider;
    private ConsentGraphRedisRepository graphRepo;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "redis-graph";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public Class<ConsentGraphRedisConfig> getConfigurationType() {
        return ConsentGraphRedisConfig.class;
    }

    @Override
    public void configure(ConsentGraphRedisConfig config) {
        configure(JedisProvider.shared(RedisContextFactory.createClient(config)));
    }

    public void configure(JedisProvider provider) {
        if (provider == null) {
            throw new NullPointerException("JedisProvider must not be null");
        }
        this.jedisProvider = provider;
        this.graphRepo = new ConsentGraphRedisRepository(provider);
        this.configured = true;
    }

    @Override
    public boolean isReady() {
        if (!configured || jedisProvider == null) return false;
        try {
            return "PONG".equals(jedisProvider.get().ping());
        } catch (Exception e) {
            return false;
        }
    }

    public ConsentGraphRedisRepository graphRepository() {
        ensureConfigured();
        return graphRepo;
    }

    public JedisProvider jedisProvider() {
        ensureConfigured();
        return jedisProvider;
    }

    // -----------------------------------------------------------------------
    // ConsentDecisionService — evaluateConsent
    // -----------------------------------------------------------------------

    @Override
    public ConsentResponse evaluateConsent(String subject, Set<ConsentScope> accessRequests) {
        ensureConfigured();

        if (accessRequests == null || accessRequests.isEmpty()) {
            return ConsentResponse.to(
                    subject,
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    Collections.emptyMap());
        }

        // Build 2N ep keys: field-level + category-level per scope
        List<ConsentScope> scopeList = new ArrayList<>(accessRequests);
        List<String> fieldKeys = new ArrayList<>(scopeList.size());
        List<String> categoryKeys = new ArrayList<>(scopeList.size());

        for (ConsentScope scope : scopeList) {
            String fieldPolicyName =
                    "FIELD:"
                            + qualifyField(scope.entityName(), scope.fieldName())
                            + ":"
                            + scope.purposeName();
            fieldKeys.add(graphRepo.epKey("subject", subject, "policy", fieldPolicyName));

            String catPolicyName = "CAT:" + scope.category() + ":" + scope.purposeName();
            categoryKeys.add(graphRepo.epKey("subject", subject, "policy", catPolicyName));
        }

        // Single pipeline: 2N HGETs
        List<String> fieldResults = graphRepo.batchGetEdgePropField(fieldKeys, "action");
        List<String> categoryResults = graphRepo.batchGetEdgePropField(categoryKeys, "action");

        // Apply field-over-category precedence
        Set<ConsentScope> granted = new HashSet<>();
        Set<ConsentScope> denied = new HashSet<>();
        Map<String, String> reasons = new HashMap<>();
        Map<ConsentScope, DecisionDetail> details = new HashMap<>();

        for (int i = 0; i < scopeList.size(); i++) {
            ConsentScope scope = scopeList.get(i);
            String fieldAction = fieldResults.get(i);
            String catAction = categoryResults.get(i);

            String effectiveStatus;
            String resolvedBy;

            if (fieldAction != null) {
                effectiveStatus = normalizeAction(fieldAction);
                resolvedBy = DecisionDetail.RESOLVED_BY_FIELD;
            } else if (catAction != null) {
                effectiveStatus = normalizeAction(catAction);
                resolvedBy = DecisionDetail.RESOLVED_BY_CATEGORY;
            } else {
                effectiveStatus = null;
                resolvedBy = DecisionDetail.RESOLVED_BY_NONE;
            }

            DecisionDetail detail = new DecisionDetail(scope, effectiveStatus, resolvedBy, null);
            details.put(scope, detail);

            if ("GRANTED".equals(effectiveStatus)) {
                granted.add(scope);
            } else {
                denied.add(scope);
                reasons.put(scope.fieldName(), buildReasonMessage(detail));
            }
        }

        return ConsentResponse.to(subject, granted, denied, reasons, details);
    }

    // -----------------------------------------------------------------------
    // ConsentGraphQueryService — getConsentGraph
    // -----------------------------------------------------------------------

    @Override
    public ConsentGraph getConsentGraph(
            String cursor, int limit, String rootType, String rootName, Integer depth) {
        ensureConfigured();

        if (rootType != null && rootName != null) {
            return getSubgraph(rootType, rootName, depth, cursor, limit);
        }
        return getFullGraph(cursor, limit);
    }

    private ConsentGraph getFullGraph(String cursor, int limit) {
        // Cursor format: "{type}:{name}" — decode to position
        String cursorType = null;
        String cursorName = null;
        if (cursor != null && !cursor.isEmpty()) {
            String[] parts = ConsentGraphRedisRepository.splitEdgeMember(cursor);
            if (parts != null) {
                cursorType = parts[0];
                cursorName = parts[1];
            }
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        int remaining = limit;
        boolean started = (cursorType == null);
        String lastType = null;
        String lastName = null;

        for (String type : ConsentGraphRedisRepository.NODE_TYPES) {
            if (remaining <= 0) break;

            if (!started) {
                if (!type.equals(cursorType)) continue;
                started = true;
            }

            String typeCursor = type.equals(cursorType) ? cursorName : null;
            List<String> names = graphRepo.listNodesByType(type, typeCursor, remaining);

            for (String name : names) {
                if (remaining <= 0) break;
                Map<String, String> props = graphRepo.getNode(type, name);
                nodes.add(new GraphNode(type, name, props));
                lastType = type;
                lastName = name;
                remaining--;

                // Collect out-edges for this node
                collectEdgesForNode(type, name, edges);
            }
        }

        int totalNodes = 0;
        for (String type : ConsentGraphRedisRepository.NODE_TYPES) {
            totalNodes += (int) graphRepo.countNodesByType(type);
        }

        boolean hasMore = remaining == 0;
        String nextCursor = hasMore && lastType != null ? lastType + ":" + lastName : null;

        return new ConsentGraph(
                nodes, edges, new GraphPageInfo(nextCursor, hasMore, totalNodes, edges.size()));
    }

    private ConsentGraph getSubgraph(
            String rootType, String rootName, Integer depth, String cursor, int limit) {
        Map<String, Integer> visited = graphRepo.traverse(rootType, rootName, depth);

        // Apply cursor-based pagination over the traversal result
        List<String> allKeys = new ArrayList<>(visited.keySet());
        int startIdx = 0;
        if (cursor != null && !cursor.isEmpty()) {
            int idx = allKeys.indexOf(cursor);
            if (idx >= 0) startIdx = idx + 1;
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        int end = Math.min(startIdx + limit, allKeys.size());

        Set<String> pageNodeKeys = new HashSet<>();
        for (int i = startIdx; i < end; i++) {
            String key = allKeys.get(i);
            String[] parts = ConsentGraphRedisRepository.splitEdgeMember(key);
            if (parts == null) continue;

            Map<String, String> props = graphRepo.getNode(parts[0], parts[1]);
            nodes.add(new GraphNode(parts[0], parts[1], props));
            pageNodeKeys.add(key);
        }

        // Collect edges between nodes in this page and the full visited set
        for (String nodeKey : pageNodeKeys) {
            String[] parts = ConsentGraphRedisRepository.splitEdgeMember(nodeKey);
            if (parts == null) continue;
            Set<String> outMembers = graphRepo.getOutEdges(parts[0], parts[1]);
            for (String member : outMembers) {
                if (visited.containsKey(member)) {
                    String[] target = ConsentGraphRedisRepository.splitEdgeMember(member);
                    if (target != null) {
                        Map<String, String> ep =
                                graphRepo.getEdgeProps(parts[0], parts[1], target[0], target[1]);
                        edges.add(
                                new GraphEdge(
                                        parts[0],
                                        parts[1],
                                        target[0],
                                        target[1],
                                        ep.isEmpty() ? Collections.emptyMap() : ep));
                    }
                }
            }
        }

        boolean hasMore = end < allKeys.size();
        String nextCursor = hasMore ? allKeys.get(end - 1) : null;

        return new ConsentGraph(
                nodes, edges, new GraphPageInfo(nextCursor, hasMore, visited.size(), edges.size()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void collectEdgesForNode(String type, String name, List<GraphEdge> edges) {
        Set<String> outMembers = graphRepo.getOutEdges(type, name);
        for (String member : outMembers) {
            String[] target = ConsentGraphRedisRepository.splitEdgeMember(member);
            if (target != null) {
                Map<String, String> ep = graphRepo.getEdgeProps(type, name, target[0], target[1]);
                edges.add(
                        new GraphEdge(
                                type,
                                name,
                                target[0],
                                target[1],
                                ep.isEmpty() ? Collections.emptyMap() : ep));
            }
        }
    }

    private static String qualifyField(String entity, String field) {
        String e =
                (entity != null && !entity.isBlank() && !"*".equals(entity)) ? entity : "_default_";
        // Normalize entity and field names to snake_case so that
        // camelCase / PascalCase / SCREAMING_SNAKE_CASE all match the canonical seed data.
        e = FieldNameNormalizer.normalize(e);
        if (e == null || e.isBlank()) e = "_default_";
        String f = FieldNameNormalizer.normalize(field);
        if (f == null) f = field;
        return e + "/" + f;
    }

    private static String normalizeAction(String action) {
        if (action == null) return null;
        return switch (action) {
            case "GRANT", "GRANTED" -> "GRANTED";
            case "REVOKE", "REVOKED" -> "REVOKED";
            case "EXPIRE", "EXPIRED" -> "EXPIRED";
            default -> action;
        };
    }

    private static String buildReasonMessage(DecisionDetail detail) {
        if (detail == null || detail.effectiveStatus() == null) {
            return REASON_NO_RECORD;
        }
        if ("EXPIRED".equals(detail.effectiveStatus())) {
            return "Consent expired";
        }
        return REASON_REVOKED;
    }

    private void ensureConfigured() {
        if (!configured) {
            throw new CveilConfigurationException(
                    "ConsentDecisionQueryServiceRedis has not been configured. "
                            + "Call configure() first.");
        }
    }

    // -----------------------------------------------------------------------
    // Cache key operations (SCAN-based, delegated to repository)
    // -----------------------------------------------------------------------

    @Override
    public long countCachedKeysForSubject(String subject) {
        ensureConfigured();
        return graphRepo.countCachedKeysForSubject(subject);
    }

    @Override
    public long deleteCachedKeysForSubject(String subject) {
        ensureConfigured();
        return graphRepo.deleteCachedKeysForSubject(subject);
    }
}
