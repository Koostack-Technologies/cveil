package com.koostack.cveil.tenant.consent.projection.decision;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

/**
 * Redis repository for the consent property graph.
 *
 * <p>Stores nodes as Hashes, edges as bidirectional Sets, edge properties as Hashes, and node type
 * indexes as Sorted Sets (for cursor pagination via {@code ZRANGEBYLEX}).
 *
 * <h2>Redis Key Schema</h2>
 *
 * <pre>
 *   Nodes:          {t}:node:{type}:{name}                      (Hash)
 *   Out-edges:      {t}:out:{type}:{name}                       (Set of "targetType:targetName")
 *   In-edges:       {t}:in:{type}:{name}                        (Set of "sourceType:sourceName")
 *   Edge props:     {t}:ep:{fromType}:{fromName}:{toType}:{toName}  (Hash)
 *   Type indexes:   {t}:idx:{type}                              (Sorted Set, score=0)
 * </pre>
 *
 * <h2>Multi-Tenancy</h2>
 *
 * <p>All keys are prefixed with the current tenant from {@link TenantContext#require()}.
 */
public class ConsentGraphRedisRepository {

    private static final Logger log = LoggerFactory.getLogger(ConsentGraphRedisRepository.class);

    private final JedisProvider jedisProvider;

    public ConsentGraphRedisRepository(JedisProvider jedisProvider) {
        this.jedisProvider = Objects.requireNonNull(jedisProvider, "jedisProvider");
    }

    public ConsentGraphRedisRepository(JedisPooled jedis) {
        this(JedisProvider.shared(jedis));
    }

    public JedisProvider jedisProvider() {
        return jedisProvider;
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    private String prefix() {
        return TenantContext.require();
    }

    String nodeKey(String type, String name) {
        return prefix() + ":node:" + type + ":" + name;
    }

    String outKey(String type, String name) {
        return prefix() + ":out:" + type + ":" + name;
    }

    String inKey(String type, String name) {
        return prefix() + ":in:" + type + ":" + name;
    }

    String epKey(String fromType, String fromName, String toType, String toName) {
        return prefix() + ":ep:" + fromType + ":" + fromName + ":" + toType + ":" + toName;
    }

    String idxKey(String type) {
        return prefix() + ":idx:" + type;
    }

    // -----------------------------------------------------------------------
    // Node operations
    // -----------------------------------------------------------------------

    /** Creates or replaces a node. Adds the node to the type index. */
    public void putNode(String type, String name, Map<String, String> properties) {
        try (Pipeline p = jedisProvider.get().pipelined()) {
            if (properties != null && !properties.isEmpty()) {
                p.hset(nodeKey(type, name), properties);
            } else {
                p.hset(nodeKey(type, name), Map.of("_type", type, "_name", name));
            }
            p.zadd(idxKey(type), 0, name);
            p.sync();
        }
    }

    /**
     * Removes a node and cascades: deletes all connected edges (both directions) and their edge
     * properties, then removes from the type index.
     */
    public void removeNode(String type, String name) {
        JedisPooled jedis = jedisProvider.get();

        // Collect connected edges first
        Set<String> outMembers = jedis.smembers(outKey(type, name));
        Set<String> inMembers = jedis.smembers(inKey(type, name));

        try (Pipeline p = jedis.pipelined()) {
            // Remove out-edges
            if (outMembers != null) {
                for (String member : outMembers) {
                    String[] parts = splitEdgeMember(member);
                    if (parts != null) {
                        p.srem(inKey(parts[0], parts[1]), type + ":" + name);
                        p.del(epKey(type, name, parts[0], parts[1]));
                    }
                }
            }
            // Remove in-edges
            if (inMembers != null) {
                for (String member : inMembers) {
                    String[] parts = splitEdgeMember(member);
                    if (parts != null) {
                        p.srem(outKey(parts[0], parts[1]), type + ":" + name);
                        p.del(epKey(parts[0], parts[1], type, name));
                    }
                }
            }
            // Delete the node itself, its edge sets, and index entry
            p.del(nodeKey(type, name));
            p.del(outKey(type, name));
            p.del(inKey(type, name));
            p.zrem(idxKey(type), name);
            p.sync();
        }
    }

    /** Returns the properties of a node, or empty map if the node does not exist. */
    public Map<String, String> getNode(String type, String name) {
        Map<String, String> result = jedisProvider.get().hgetAll(nodeKey(type, name));
        return result != null ? result : Collections.emptyMap();
    }

    /** Returns {@code true} if the node exists in Redis. */
    public boolean nodeExists(String type, String name) {
        return jedisProvider.get().exists(nodeKey(type, name));
    }

    // -----------------------------------------------------------------------
    // Edge operations
    // -----------------------------------------------------------------------

    /**
     * Adds a directed edge between two nodes. Updates both out-set and in-set. Optionally stores
     * edge properties.
     */
    public void addEdge(
            String fromType,
            String fromName,
            String toType,
            String toName,
            Map<String, String> edgeProps) {
        try (Pipeline p = jedisProvider.get().pipelined()) {
            p.sadd(outKey(fromType, fromName), toType + ":" + toName);
            p.sadd(inKey(toType, toName), fromType + ":" + fromName);
            if (edgeProps != null && !edgeProps.isEmpty()) {
                p.hset(epKey(fromType, fromName, toType, toName), edgeProps);
            }
            p.sync();
        }
    }

    /** Removes a directed edge between two nodes and its edge properties. */
    public void removeEdge(String fromType, String fromName, String toType, String toName) {
        try (Pipeline p = jedisProvider.get().pipelined()) {
            p.srem(outKey(fromType, fromName), toType + ":" + toName);
            p.srem(inKey(toType, toName), fromType + ":" + fromName);
            p.del(epKey(fromType, fromName, toType, toName));
            p.sync();
        }
    }

    /** Updates (overwrites) edge properties on an existing edge. */
    public void setEdgeProps(
            String fromType,
            String fromName,
            String toType,
            String toName,
            Map<String, String> edgeProps) {
        JedisPooled jedis = jedisProvider.get();
        String key = epKey(fromType, fromName, toType, toName);
        try (Pipeline p = jedis.pipelined()) {
            p.del(key);
            if (edgeProps != null && !edgeProps.isEmpty()) {
                p.hset(key, edgeProps);
            }
            p.sync();
        }
    }

    /**
     * Batch update of edge properties across multiple edges in a single pipeline. Each entry maps
     * an ep key to the properties to set.
     */
    public void batchSetEdgeProps(Map<String, Map<String, String>> keyToProps) {
        if (keyToProps == null || keyToProps.isEmpty()) return;
        JedisPooled jedis = jedisProvider.get();
        try (Pipeline p = jedis.pipelined()) {
            for (var entry : keyToProps.entrySet()) {
                p.del(entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    p.hset(entry.getKey(), entry.getValue());
                }
            }
            p.sync();
        }
    }

    /** Returns all edge properties, or empty map if no properties exist. */
    public Map<String, String> getEdgeProps(
            String fromType, String fromName, String toType, String toName) {
        Map<String, String> result =
                jedisProvider.get().hgetAll(epKey(fromType, fromName, toType, toName));
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Returns a single edge property field, or {@code null} if not found. Optimized for
     * evaluateConsent — single HGET per scope.
     */
    public String getEdgePropField(
            String fromType, String fromName, String toType, String toName, String field) {
        return jedisProvider.get().hget(epKey(fromType, fromName, toType, toName), field);
    }

    /**
     * Batch HGET of a single field from multiple edge property keys. Returns results in same order
     * as input keys. Uses a pipeline for a single round trip.
     */
    public List<String> batchGetEdgePropField(List<String> epKeys, String field) {
        JedisPooled jedis = jedisProvider.get();
        List<Response<String>> responses = new ArrayList<>(epKeys.size());
        try (Pipeline p = jedis.pipelined()) {
            for (String key : epKeys) {
                responses.add(p.hget(key, field));
            }
            p.sync();
        }
        List<String> results = new ArrayList<>(epKeys.size());
        for (Response<String> r : responses) {
            results.add(r.get());
        }
        return results;
    }

    /** Returns all out-edge members ({@code "targetType:targetName"}) from the node. */
    public Set<String> getOutEdges(String type, String name) {
        Set<String> result = jedisProvider.get().smembers(outKey(type, name));
        return result != null ? result : Collections.emptySet();
    }

    /** Returns all in-edge members ({@code "sourceType:sourceName"}) to the node. */
    public Set<String> getInEdges(String type, String name) {
        Set<String> result = jedisProvider.get().smembers(inKey(type, name));
        return result != null ? result : Collections.emptySet();
    }

    // -----------------------------------------------------------------------
    // Index / pagination
    // -----------------------------------------------------------------------

    /** All recognized node types, in canonical traversal order. */
    public static final String[] NODE_TYPES = {
        "entity", "field", "category", "purpose", "regulation", "jurisdiction", "policy", "subject"
    };

    /**
     * Lists node names of a given type using cursor-based lexicographic pagination on the sorted
     * set index.
     *
     * @param type the node type
     * @param cursor the exclusive lower-bound name (pass {@code null} or empty string for the first
     *     page)
     * @param limit maximum number of names to return
     * @return list of node names, ordered lexicographically
     */
    public List<String> listNodesByType(String type, String cursor, int limit) {
        String key = idxKey(type);
        String min = (cursor == null || cursor.isEmpty()) ? "-" : "(" + cursor;
        return jedisProvider.get().zrangeByLex(key, min, "+", 0, limit);
    }

    /** Returns the total count of nodes of the given type. */
    public long countNodesByType(String type) {
        return jedisProvider.get().zcard(idxKey(type));
    }

    // -----------------------------------------------------------------------
    // Graph traversal
    // -----------------------------------------------------------------------

    /**
     * Performs a breadth-first traversal from a starting node up to {@code maxDepth} hops,
     * collecting all reachable nodes.
     *
     * @param startType the starting node type
     * @param startName the starting node name
     * @param maxDepth maximum depth ({@code null} for unlimited)
     * @return ordered map of {@code "type:name"} → depth, including the start node at depth 0
     */
    public Map<String, Integer> traverse(String startType, String startName, Integer maxDepth) {
        Map<String, Integer> visited = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();

        String startKey = startType + ":" + startName;
        visited.put(startKey, 0);
        queue.add(startKey);

        JedisPooled jedis = jedisProvider.get();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = visited.get(current);
            if (maxDepth != null && currentDepth >= maxDepth) {
                continue;
            }

            String[] parts = splitEdgeMember(current);
            if (parts == null) continue;

            // Collect neighbors from both directions
            Set<String> outNeighbors = jedis.smembers(outKey(parts[0], parts[1]));
            Set<String> inNeighbors = jedis.smembers(inKey(parts[0], parts[1]));

            Set<String> allNeighbors = new HashSet<>();
            if (outNeighbors != null) allNeighbors.addAll(outNeighbors);
            if (inNeighbors != null) allNeighbors.addAll(inNeighbors);

            for (String neighbor : allNeighbors) {
                if (!visited.containsKey(neighbor)) {
                    visited.put(neighbor, currentDepth + 1);
                    queue.add(neighbor);
                }
            }
        }

        return visited;
    }

    // -----------------------------------------------------------------------
    // Expired consent edge scanning
    // -----------------------------------------------------------------------

    /**
     * Scans for subject→policy edge property keys whose {@code expiresAt} timestamp is before the
     * given cutoff. Returns a list of ep keys that are expired and whose {@code action} is not
     * already {@code "EXPIRE"}.
     *
     * <p>Uses {@code SCAN} with a glob pattern to iterate all consent ep keys, then pipelines
     * {@code HMGET expiresAt action} for each batch.
     *
     * @param cutoff the instant to compare against
     * @param scanCountHint advisory hint for Redis SCAN COUNT (actual batch sizes may vary)
     * @return list of expired ep keys that need cleanup
     */
    public List<String> scanExpiredConsentEdges(java.time.Instant cutoff, int scanCountHint) {
        JedisPooled jedis = jedisProvider.get();
        String pattern = prefix() + ":ep:subject:*:policy:*";
        List<String> expired = new ArrayList<>();

        String cursor = "0";
        redis.clients.jedis.params.ScanParams scanParams =
                new redis.clients.jedis.params.ScanParams().match(pattern).count(scanCountHint);

        do {
            redis.clients.jedis.resps.ScanResult<String> result = jedis.scan(cursor, scanParams);
            List<String> keys = result.getResult();
            cursor = result.getCursor();

            if (keys.isEmpty()) continue;

            // Pipeline HMGET expiresAt, action for all keys in this batch
            List<Response<List<String>>> responses = new ArrayList<>(keys.size());
            try (Pipeline p = jedis.pipelined()) {
                for (String key : keys) {
                    responses.add(p.hmget(key, "expiresAt", "action"));
                }
                p.sync();
            }

            for (int i = 0; i < keys.size(); i++) {
                List<String> vals = responses.get(i).get();
                String expiresAtStr = vals.get(0);
                String action = vals.get(1);
                if (expiresAtStr == null || "EXPIRE".equals(action)) continue;
                try {
                    java.time.Instant expiresAt = java.time.Instant.parse(expiresAtStr);
                    if (expiresAt.isBefore(cutoff)) {
                        expired.add(keys.get(i));
                    }
                } catch (Exception e) {
                    // skip unparseable timestamps
                }
            }
        } while (!"0".equals(cursor));

        return expired;
    }

    /**
     * Atomically marks the given ep keys as expired, but ONLY if {@code expiresAt} is still before
     * the cutoff and {@code action} is not already {@code "EXPIRE"}.
     *
     * <p>Uses a Lua script per key to avoid TOCTOU races where a concurrent grant could be
     * overwritten by the reaper.
     *
     * @param epKeys the edge property keys to mark expired
     * @param cutoff the expiration cutoff used during scanning
     * @return the number of keys actually marked expired
     */
    public int markEdgesExpired(List<String> epKeys, java.time.Instant cutoff) {
        if (epKeys == null || epKeys.isEmpty()) return 0;
        String now = java.time.Instant.now().toString();
        String cutoffStr = cutoff.toString();
        JedisPooled jedis = jedisProvider.get();

        // Lua: atomically check expiresAt < cutoff AND action != "EXPIRE" before updating
        String lua =
                """
                local ea = redis.call('HGET', KEYS[1], 'expiresAt')
                if ea == false then return 0 end
                local act = redis.call('HGET', KEYS[1], 'action')
                if act == 'EXPIRE' then return 0 end
                if ea > ARGV[1] then return 0 end
                redis.call('HSET', KEYS[1], 'action', 'EXPIRE', 'updatedAt', ARGV[2])
                return 1
                """;

        int marked = 0;
        for (String key : epKeys) {
            Object result = jedis.eval(lua, 1, key, cutoffStr, now);
            if (result instanceof Long l && l == 1) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * Extracts the subject ID from a consent ep key. Key format: {@code
     * {tenant}:ep:subject:{subjectId}:policy:{policyName}} Returns the subjectId, or {@code null}
     * if the key doesn't match.
     */
    public static String extractSubjectFromEpKey(String epKey) {
        // Find ":ep:subject:" then extract until ":policy:"
        int epSubject = epKey.indexOf(":ep:subject:");
        if (epSubject < 0) return null;
        int start = epSubject + ":ep:subject:".length();
        int policyMarker = epKey.indexOf(":policy:", start);
        if (policyMarker < 0) return null;
        return epKey.substring(start, policyMarker);
    }

    // -----------------------------------------------------------------------
    // Subject cache key operations (SCAN-based)
    // -----------------------------------------------------------------------

    /**
     * Counts the number of edge-property keys for a given subject using SCAN. Pattern: {@code
     * {prefix}:ep:subject:{subjectName}:policy:*}
     */
    long countCachedKeysForSubject(String subjectName) {
        JedisPooled jedis = jedisProvider.get();
        String pattern = prefix() + ":ep:subject:" + subjectName + ":policy:*";
        long count = 0;
        String cursor = "0";
        redis.clients.jedis.params.ScanParams scanParams =
                new redis.clients.jedis.params.ScanParams().match(pattern).count(200);
        do {
            redis.clients.jedis.resps.ScanResult<String> result = jedis.scan(cursor, scanParams);
            count += result.getResult().size();
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        return count;
    }

    /**
     * Deletes all edge-property keys for a given subject using SCAN + pipelined DEL. Returns the
     * number of keys deleted. Pattern: {@code {prefix}:ep:subject:{subjectName}:policy:*}
     */
    long deleteCachedKeysForSubject(String subjectName) {
        JedisPooled jedis = jedisProvider.get();
        String pattern = prefix() + ":ep:subject:" + subjectName + ":policy:*";
        long deleted = 0;
        String cursor = "0";
        redis.clients.jedis.params.ScanParams scanParams =
                new redis.clients.jedis.params.ScanParams().match(pattern).count(200);
        do {
            redis.clients.jedis.resps.ScanResult<String> result = jedis.scan(cursor, scanParams);
            List<String> keys = result.getResult();
            cursor = result.getCursor();
            if (!keys.isEmpty()) {
                try (Pipeline p = jedis.pipelined()) {
                    for (String key : keys) {
                        p.del(key);
                    }
                    p.sync();
                }
                deleted += keys.size();
            }
        } while (!"0".equals(cursor));
        return deleted;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Splits an edge member string ({@code "type:name"}) into [type, name]. Handles names that
     * contain {@code :} by splitting on the first occurrence only.
     */
    static String[] splitEdgeMember(String member) {
        if (member == null) return null;
        int idx = member.indexOf(':');
        if (idx <= 0 || idx >= member.length() - 1) return null;
        return new String[] {member.substring(0, idx), member.substring(idx + 1)};
    }
}
