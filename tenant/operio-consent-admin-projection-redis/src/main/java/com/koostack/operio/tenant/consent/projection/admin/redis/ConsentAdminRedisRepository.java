package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Repository for consent admin aggregate storage in Redis using JSON Strings
 * with Set-based indexes for efficient list operations.
 *
 * <h2>Redis Key Schema</h2>
 * <pre>
 *   Individual keys:
 *     {tenantId}:admin:field:{entityName}:{fieldName}       → JSON FieldView
 *     {tenantId}:admin:purpose:{purposeName}                → JSON PurposeView
 *     {tenantId}:admin:regulation:{regulationName}          → JSON RegulationView
 *     {tenantId}:admin:consent:{subjectId}                  → JSON SubjectConsentView
 *
 *   Index sets:
 *     {tenantId}:admin:field-index                          → Set of "{entityName}:{fieldName}"
 *     {tenantId}:admin:purpose-index                        → Set of "{purposeName}"
 *     {tenantId}:admin:regulation-index                     → Set of "{regulationName}"
 *     {tenantId}:admin:consent-index                        → Set of "{subjectId}"
 * </pre>
 *
 * <h2>Multi-Tenancy</h2>
 * All keys are prefixed by {@link TenantContext#require()} — the tenant must
 * be set before any operation.
 */
public class ConsentAdminRedisRepository {

    private static final Logger log = LoggerFactory.getLogger(ConsentAdminRedisRepository.class);

    private final JedisProvider jedisProvider;
    private final ObjectMapper objectMapper;

    public ConsentAdminRedisRepository(JedisProvider jedisProvider, ObjectMapper objectMapper) {
        this.jedisProvider = Objects.requireNonNull(jedisProvider, "jedisProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    // ── Field Operations ────────────────────────────────────────────────────

    public void saveField(FieldView field) {
        String tenantId = TenantContext.require();
        String member = fieldMember(field.entity().entityName(), field.fieldName());
        String key = tenantId + ":admin:field:" + member;
        String indexKey = tenantId + ":admin:field-index";

        try (Pipeline p = jedis().pipelined()) {
            p.set(key, serialize(field));
            p.sadd(indexKey, member);
        }
    }

    public void removeField(String entityName, String fieldName) {
        String tenantId = TenantContext.require();
        String member = fieldMember(entityName, fieldName);
        String key = tenantId + ":admin:field:" + member;
        String indexKey = tenantId + ":admin:field-index";

        try (Pipeline p = jedis().pipelined()) {
            p.del(key);
            p.srem(indexKey, member);
        }
    }

    public FieldView getField(String fieldName, String entityName) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:field:" + fieldMember(entityName, fieldName);
        String json = jedis().get(key);
        return json == null ? null : deserialize(json, FieldView.class);
    }

    public List<FieldView> listFields() {
        String tenantId = TenantContext.require();
        String indexKey = tenantId + ":admin:field-index";
        return listFromIndex(indexKey, tenantId + ":admin:field:", FieldView.class);
    }

    // ── Purpose Operations ──────────────────────────────────────────────────

    public void savePurpose(PurposeView purpose) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:purpose:" + purpose.purposeName();
        String indexKey = tenantId + ":admin:purpose-index";

        try (Pipeline p = jedis().pipelined()) {
            p.set(key, serialize(purpose));
            p.sadd(indexKey, purpose.purposeName());
        }
    }

    public void removePurpose(String purposeName) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:purpose:" + purposeName;
        String indexKey = tenantId + ":admin:purpose-index";

        try (Pipeline p = jedis().pipelined()) {
            p.del(key);
            p.srem(indexKey, purposeName);
        }
    }

    public PurposeView getPurpose(String purposeName) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:purpose:" + purposeName;
        String json = jedis().get(key);
        return json == null ? null : deserialize(json, PurposeView.class);
    }

    public List<PurposeView> listPurposes() {
        String tenantId = TenantContext.require();
        String indexKey = tenantId + ":admin:purpose-index";
        return listFromIndex(indexKey, tenantId + ":admin:purpose:", PurposeView.class);
    }

    // ── Regulation Operations ───────────────────────────────────────────────

    public void saveRegulation(RegulationView regulation) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:regulation:" + regulation.regulationName();
        String indexKey = tenantId + ":admin:regulation-index";

        try (Pipeline p = jedis().pipelined()) {
            p.set(key, serialize(regulation));
            p.sadd(indexKey, regulation.regulationName());
        }
    }

    public void removeRegulation(String regulationName) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:regulation:" + regulationName;
        String indexKey = tenantId + ":admin:regulation-index";

        try (Pipeline p = jedis().pipelined()) {
            p.del(key);
            p.srem(indexKey, regulationName);
        }
    }

    public RegulationView getRegulation(String regulationName) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:regulation:" + regulationName;
        String json = jedis().get(key);
        return json == null ? null : deserialize(json, RegulationView.class);
    }

    public List<RegulationView> listRegulations() {
        String tenantId = TenantContext.require();
        String indexKey = tenantId + ":admin:regulation-index";
        return listFromIndex(indexKey, tenantId + ":admin:regulation:", RegulationView.class);
    }

    // ── SubjectConsent Operations ───────────────────────────────────────────

    public void saveSubjectConsent(SubjectConsentView consent) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:consent:" + consent.subjectId();
        String indexKey = tenantId + ":admin:consent-index";

        try (Pipeline p = jedis().pipelined()) {
            p.set(key, serialize(consent));
            p.sadd(indexKey, consent.subjectId());
        }
    }

    public void removeSubjectConsent(String subjectId) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:consent:" + subjectId;
        String indexKey = tenantId + ":admin:consent-index";

        try (Pipeline p = jedis().pipelined()) {
            p.del(key);
            p.srem(indexKey, subjectId);
        }
    }

    public SubjectConsentView getSubjectConsent(String subjectId) {
        String tenantId = TenantContext.require();
        String key = tenantId + ":admin:consent:" + subjectId;
        String json = jedis().get(key);
        return json == null ? null : deserialize(json, SubjectConsentView.class);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private <T> List<T> listFromIndex(String indexKey, String keyPrefix, Class<T> type) {
        Set<String> members = jedis().smembers(indexKey);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }

        String[] keys = members.stream()
                .map(m -> keyPrefix + m)
                .toArray(String[]::new);

        List<String> values = jedis().mget(keys);
        List<T> result = new ArrayList<>(values.size());
        for (String json : values) {
            if (json != null) {
                T item = deserialize(json, type);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize admin aggregate to JSON", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize admin aggregate from Redis — type={}", type.getSimpleName(), e);
            return null;
        }
    }

    private static String fieldMember(String entityName, String fieldName) {
        return entityName + ":" + fieldName;
    }

    private JedisPooled jedis() {
        return jedisProvider.get();
    }
}
