package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationPublisher;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationSubscriber;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSubscriber;
import org.junit.jupiter.api.*;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests using jedis-mock for the full consent admin Redis projection
 * pipeline: event → handler → Redis → query service, plus pub/sub invalidation.
 */
@DisplayName("Consent Admin Redis Projection (Integration)")
class ConsentAdminRedisProjectionIntegrationTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String ADMIN_CHANNEL_PREFIX = "operio:consent-admin-invalidation:";
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private RedisServer redisServer;
    private JedisPooled jedis;
    private ConsentAdminRedisRepository repository;
    private RedisInvalidationPublisher publisher;
    private ConsentAdminRedisProjectionHandler handler;
    private RedisConsentAdminQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(redisServer.getHost(), redisServer.getBindPort());

        JedisProvider provider = JedisProvider.shared(jedis);
        repository = new ConsentAdminRedisRepository(provider, mapper);
        publisher = new RedisInvalidationPublisher(jedis, mapper, ADMIN_CHANNEL_PREFIX);
        handler = new ConsentAdminRedisProjectionHandler(repository, publisher);
        queryService = new RedisConsentAdminQueryService(repository);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (jedis != null) jedis.close();
        if (redisServer != null) redisServer.stop();
    }

    private Map<String, String> eventFields(String eventType, Object payload) throws Exception {
        return Map.of(
                TenantEvent.FIELD_TENANT_ID, TENANT_ID,
                TenantEvent.FIELD_EVENT_TYPE, eventType,
                TenantEvent.FIELD_PAYLOAD, mapper.writeValueAsString(payload)
        );
    }

    private void fireEvent(String eventType, Object payload) throws Exception {
        Map<String, String> fields = eventFields(eventType, payload);
        TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));
    }

    // ── Field End-to-End ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Field Projection E2E")
    class FieldE2E {

        @Test
        @DisplayName("register → update → add category → query round-trip")
        void fullFieldLifecycle() throws Exception {
            // Register
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "email", "description", "User email"));

            TenantContext.runWhere(TENANT_ID, () -> {
                FieldView field = queryService.getField("email", "user");
                assertNotNull(field);
                assertEquals("email", field.fieldName());
                assertEquals("user", field.entity().entityName());
                assertEquals("User email", field.description());
                assertTrue(field.categories().isEmpty());

                List<FieldView> allFields = queryService.listFields();
                assertNotNull(allFields);
                assertEquals(1, allFields.size());
            });

            // Update description
            fireEvent(ConsentEventTypes.FIELD_UPDATED,
                    Map.of("entityName", "user", "fieldName", "email", "description", "Updated email",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "description", "Updated email", "categoryNames", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> {
                FieldView field = queryService.getField("email", "user");
                assertEquals("Updated email", field.description());
            });

            // Assign category
            fireEvent(ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "description", "Updated email", "categoryNames", List.of("PII"))));

            TenantContext.runWhere(TENANT_ID, () -> {
                FieldView field = queryService.getField("email", "user");
                assertEquals(1, field.categories().size());
                assertEquals("PII", field.categories().get(0).categoryName());
            });

            // Unassign category
            fireEvent(ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "description", "Updated email", "categoryNames", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> {
                FieldView field = queryService.getField("email", "user");
                assertTrue(field.categories().isEmpty());
            });

            // Remove
            fireEvent(ConsentEventTypes.FIELD_REMOVED,
                    Map.of("entityName", "user", "fieldName", "email"));

            TenantContext.runWhere(TENANT_ID, () -> {
                assertNull(queryService.getField("email", "user"));
                assertNull(queryService.listFields()); // empty → null in RedisConsentAdminQueryService
            });
        }

        @Test
        @DisplayName("multiple fields from different entities")
        void multipleEntities() throws Exception {
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "email"));
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "name"));
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "order", "fieldName", "address"));

            TenantContext.runWhere(TENANT_ID, () -> {
                List<FieldView> all = queryService.listFields();
                assertNotNull(all);
                assertEquals(3, all.size());

                assertNotNull(queryService.getField("email", "user"));
                assertNotNull(queryService.getField("name", "user"));
                assertNotNull(queryService.getField("address", "order"));
                assertNull(queryService.getField("nonexistent", "user"));
            });
        }
    }

    // ── Purpose End-to-End ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Purpose Projection E2E")
    class PurposeE2E {

        @Test
        @DisplayName("register → link regulation → add policy → query round-trip")
        void fullPurposeLifecycle() throws Exception {
            fireEvent(ConsentEventTypes.PURPOSE_REGISTERED,
                    Map.of("purposeName", "marketing", "description", "Marketing purpose"));

            TenantContext.runWhere(TENANT_ID, () -> {
                PurposeView purpose = queryService.getPurpose("marketing");
                assertNotNull(purpose);
                assertEquals("marketing", purpose.purposeName());
                assertTrue(purpose.regulations().isEmpty());
                assertTrue(purpose.policies().isEmpty());
            });

            // Link regulation
            fireEvent(ConsentEventTypes.PURPOSE_REGULATION_LINKED,
                    Map.of("purposeName", "marketing", "regulationName", "GDPR",
                            "lawfulBasis", "CONSENT",
                            "snapshot", Map.of("purposeName", "marketing", "description", "Marketing purpose",
                                    "regulations", List.of(Map.of("regulationName", "GDPR", "lawfulBasis", "CONSENT")),
                                    "policies", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> {
                PurposeView purpose = queryService.getPurpose("marketing");
                assertEquals(1, purpose.regulations().size());
                assertEquals("GDPR", purpose.regulations().get(0).regulationName());
                assertEquals("CONSENT", purpose.regulations().get(0).lawfulBasis());
            });

            // Add policy
            fireEvent(ConsentEventTypes.PURPOSE_POLICY_ADDED,
                    Map.of("purposeName", "marketing", "policyType", "CATEGORY",
                            "categoryName", "PII",
                            "snapshot", Map.of("purposeName", "marketing", "description", "Marketing purpose",
                                    "regulations", List.of(Map.of("regulationName", "GDPR", "lawfulBasis", "CONSENT")),
                                    "policies", List.of(Map.of("policyType", "CATEGORY", "categoryName", "PII")))));

            TenantContext.runWhere(TENANT_ID, () -> {
                PurposeView purpose = queryService.getPurpose("marketing");
                assertEquals(1, purpose.policies().size());
                assertEquals("CATEGORY", purpose.policies().get(0).policyType());
            });

            // Remove policy
            fireEvent(ConsentEventTypes.PURPOSE_POLICY_REMOVED,
                    Map.of("purposeName", "marketing", "policyType", "CATEGORY",
                            "categoryName", "PII",
                            "snapshot", Map.of("purposeName", "marketing", "description", "Marketing purpose",
                                    "regulations", List.of(Map.of("regulationName", "GDPR", "lawfulBasis", "CONSENT")),
                                    "policies", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> {
                PurposeView purpose = queryService.getPurpose("marketing");
                assertTrue(purpose.policies().isEmpty());
            });
        }
    }

    // ── Regulation End-to-End ──────────────────────────────────────────────

    @Nested
    @DisplayName("Regulation Projection E2E")
    class RegulationE2E {

        @Test
        @DisplayName("register → add jurisdiction → remove jurisdiction → remove")
        void fullRegulationLifecycle() throws Exception {
            fireEvent(ConsentEventTypes.REGULATION_REGISTERED,
                    Map.of("regulationName", "GDPR", "description", "EU regulation"));

            TenantContext.runWhere(TENANT_ID, () -> {
                RegulationView reg = queryService.getRegulation("GDPR");
                assertNotNull(reg);
                assertEquals("GDPR", reg.regulationName());
                assertTrue(reg.activeJurisdictions().isEmpty());
            });

            fireEvent(ConsentEventTypes.REGULATION_JURISDICTION_ADDED,
                    Map.of("regulationName", "GDPR", "jurisdictionName", "EU",
                            "snapshot", Map.of("regulationName", "GDPR", "description", "EU regulation",
                                    "jurisdictionNames", List.of("EU"))));

            TenantContext.runWhere(TENANT_ID, () -> {
                RegulationView reg = queryService.getRegulation("GDPR");
                assertEquals(1, reg.activeJurisdictions().size());
                assertEquals("EU", reg.activeJurisdictions().get(0).jurisdictionName());
            });

            // Add duplicate jurisdiction — should not create duplicates
            fireEvent(ConsentEventTypes.REGULATION_JURISDICTION_ADDED,
                    Map.of("regulationName", "GDPR", "jurisdictionName", "EU",
                            "snapshot", Map.of("regulationName", "GDPR", "description", "EU regulation",
                                    "jurisdictionNames", List.of("EU"))));

            TenantContext.runWhere(TENANT_ID, () -> {
                RegulationView reg = queryService.getRegulation("GDPR");
                assertEquals(1, reg.activeJurisdictions().size(), "Duplicate jurisdiction should not be added");
            });

            fireEvent(ConsentEventTypes.REGULATION_JURISDICTION_REMOVED,
                    Map.of("regulationName", "GDPR", "jurisdictionName", "EU",
                            "snapshot", Map.of("regulationName", "GDPR", "description", "EU regulation",
                                    "jurisdictionNames", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> {
                RegulationView reg = queryService.getRegulation("GDPR");
                assertTrue(reg.activeJurisdictions().isEmpty());
            });

            fireEvent(ConsentEventTypes.REGULATION_REMOVED,
                    Map.of("regulationName", "GDPR"));

            TenantContext.runWhere(TENANT_ID, () ->
                    assertNull(queryService.getRegulation("GDPR")));
        }
    }

    // ── SubjectConsent End-to-End ──────────────────────────────────────────

    @Nested
    @DisplayName("SubjectConsent Projection E2E")
    class ConsentE2E {

        @Test
        @DisplayName("grant → revoke → revoke all")
        void fullConsentLifecycle() throws Exception {
            fireEvent(ConsentEventTypes.CONSENT_GRANTED,
                    Map.of("subjectId", "sub-1", "policyId", 42,
                            "action", "GRANT", "purposeName", "marketing",
                            "policyType", "FIELD"));

            TenantContext.runWhere(TENANT_ID, () -> {
                SubjectConsentView consent = queryService.getSubjectConsent("sub-1");
                assertNotNull(consent);
                assertEquals(1, consent.entries().size());
                assertEquals("GRANT", consent.entries().get(0).action());
                assertEquals(42L, consent.entries().get(0).policyId());
            });

            // Grant another policy
            fireEvent(ConsentEventTypes.CONSENT_GRANTED,
                    Map.of("subjectId", "sub-1", "policyId", 99,
                            "action", "GRANT", "purposeName", "analytics",
                            "policyType", "CATEGORY"));

            TenantContext.runWhere(TENANT_ID, () -> {
                SubjectConsentView consent = queryService.getSubjectConsent("sub-1");
                assertEquals(2, consent.entries().size());
                assertEquals(2, queryService.countSubjectConsentEntries("sub-1"));
            });

            // Revoke one
            fireEvent(ConsentEventTypes.CONSENT_REVOKED,
                    Map.of("subjectId", "sub-1", "policyId", 42,
                            "action", "REVOKE", "purposeName", "marketing",
                            "policyType", "FIELD"));

            TenantContext.runWhere(TENANT_ID, () -> {
                SubjectConsentView consent = queryService.getSubjectConsent("sub-1");
                assertEquals(2, consent.entries().size());
                // policyId 42 now has REVOKE
                SubjectConsentView.ConsentLedgerEntryView entry42 = consent.entries().stream()
                        .filter(e -> e.policyId() == 42L).findFirst().orElse(null);
                assertNotNull(entry42);
                assertEquals("REVOKE", entry42.action());
            });

            // Revoke all
            fireEvent(ConsentEventTypes.CONSENT_ALL_REVOKED,
                    Map.of("subjectId", "sub-1", "actor", "admin"));

            TenantContext.runWhere(TENANT_ID, () -> {
                SubjectConsentView consent = queryService.getSubjectConsent("sub-1");
                assertNotNull(consent);
                assertTrue(consent.entries().stream().allMatch(e -> "REVOKE".equals(e.action())),
                        "All entries should be REVOKE after revoke-all");
            });
        }
    }

    // ── Composite Query Service Fallback ───────────────────────────────────

    @Nested
    @DisplayName("Composite Query Service Fallback")
    class CompositeFallback {

        @Test
        @DisplayName("returns Redis data on hit, JOOQ data on miss")
        void fallbackBehavior() throws Exception {
            // Register a field in Redis
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "email"));

            // Create a mock JOOQ service
            RedisConsentAdminQueryService rqs = new RedisConsentAdminQueryService(repository);
            com.koostack.operio.core.tenant.consent.ConsentAdminQueryService<?> mockJooq =
                    org.mockito.Mockito.mock(com.koostack.operio.core.tenant.consent.ConsentAdminQueryService.class);
            RedisFirstConsentAdminQueryService composite = new RedisFirstConsentAdminQueryService(rqs, mockJooq);

            TenantContext.runWhere(TENANT_ID, () -> {
                // Hit — served from Redis
                FieldView field = composite.getField("email", "user");
                assertNotNull(field);
                assertEquals("email", field.fieldName());
                org.mockito.Mockito.verify(mockJooq, org.mockito.Mockito.never()).getField(
                        org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());

                // Miss — falls back to JOOQ
                FieldView jooqField = new FieldView(1L, "phone", "Phone",
                        new FieldView.EntityView(1L, "user"), List.of());
                org.mockito.Mockito.when(mockJooq.getField("phone", "user")).thenReturn(jooqField);

                FieldView result = composite.getField("phone", "user");
                assertSame(jooqField, result);
            });
        }
    }

    // ── Pub/Sub Invalidation Round-Trip ────────────────────────────────────

    @Nested
    @DisplayName("Pub/Sub Invalidation")
    class PubSubInvalidation {

        @Test
        @DisplayName("projection handler publishes invalidation to admin channel")
        void invalidationPublished() throws Exception {
            // Set up subscriber on admin channel
            RedisInvalidationSubscriber subscriber =
                    new RedisInvalidationSubscriber(jedis, mapper, ADMIN_CHANNEL_PREFIX);
            Thread.sleep(200); // Let subscriber thread start

            try {
                BlockingQueue<CacheInvalidationMessage> received = new LinkedBlockingQueue<>();
                InvalidationSubscriber.Subscription sub = subscriber.subscribe(
                        TENANT_ID, received::add);

                // Fire a field event — should trigger invalidation
                fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                        Map.of("entityName", "user", "fieldName", "email"));

                // Wait for invalidation message
                CacheInvalidationMessage msg = received.poll(2, TimeUnit.SECONDS);
                assertNotNull(msg, "Expected invalidation message on admin channel");
                assertEquals(InvalidationAction.FIELD_CHANGED, msg.action());
                assertEquals(TENANT_ID, msg.tenantId());

                sub.cancel();
            } finally {
                subscriber.close();
            }
        }

        @Test
        @DisplayName("different aggregate types produce different invalidation actions")
        void differentActionsForDifferentAggregates() throws Exception {
            RedisInvalidationSubscriber subscriber =
                    new RedisInvalidationSubscriber(jedis, mapper, ADMIN_CHANNEL_PREFIX);
            Thread.sleep(200);

            try {
                BlockingQueue<CacheInvalidationMessage> received = new LinkedBlockingQueue<>();
                InvalidationSubscriber.Subscription sub = subscriber.subscribe(
                        TENANT_ID, received::add);

                // Field event
                fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                        Map.of("entityName", "user", "fieldName", "email"));
                CacheInvalidationMessage msg1 = received.poll(2, TimeUnit.SECONDS);
                assertNotNull(msg1);
                assertEquals(InvalidationAction.FIELD_CHANGED, msg1.action());

                // Purpose event
                fireEvent(ConsentEventTypes.PURPOSE_REGISTERED,
                        Map.of("purposeName", "marketing"));
                CacheInvalidationMessage msg2 = received.poll(2, TimeUnit.SECONDS);
                assertNotNull(msg2);
                assertEquals(InvalidationAction.PURPOSE_CHANGED, msg2.action());

                // Regulation event
                fireEvent(ConsentEventTypes.REGULATION_REGISTERED,
                        Map.of("regulationName", "GDPR"));
                CacheInvalidationMessage msg3 = received.poll(2, TimeUnit.SECONDS);
                assertNotNull(msg3);
                assertEquals(InvalidationAction.REGULATION_CHANGED, msg3.action());

                sub.cancel();
            } finally {
                subscriber.close();
            }
        }
    }

    // ── Tenant Isolation ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolation {

        @Test
        @DisplayName("data from one tenant is invisible to another")
        void tenantsIsolated() throws Exception {
            // Register field for tenant-1
            Map<String, String> t1Fields = Map.of(
                    TenantEvent.FIELD_TENANT_ID, "tenant-1",
                    TenantEvent.FIELD_EVENT_TYPE, ConsentEventTypes.FIELD_REGISTERED,
                    TenantEvent.FIELD_PAYLOAD, mapper.writeValueAsString(
                            Map.of("entityName", "user", "fieldName", "email")));
            TenantContext.runWhere("tenant-1", () ->
                    handler.onEvent("stream-1", "msg-1", t1Fields));

            // Verify tenant-1 sees the field
            TenantContext.runWhere("tenant-1", () -> {
                assertNotNull(queryService.getField("email", "user"));
            });

            // Verify tenant-2 does NOT see the field
            TenantContext.runWhere("tenant-2", () -> {
                assertNull(queryService.getField("email", "user"));
            });
        }
    }

    // ── Edge Cases ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("DSAR events are silently ignored")
        void dsarIgnored() throws Exception {
            // Fire a DSAR event — should be ignored
            Map<String, String> fields = eventFields("SubjectAuditDeleted",
                    Map.of("subjectId", "sub-1"));
            TenantContext.runWhere(TENANT_ID, () ->
                    handler.onEvent("stream-1", "msg-1", fields));

            // Nothing should be stored
            TenantContext.runWhere(TENANT_ID, () ->
                    assertNull(queryService.getSubjectConsent("sub-1")));
        }

        @Test
        @DisplayName("category assign on non-existent field is handled gracefully")
        void categoryAssignMissingField() throws Exception {
            // Assign category to a field that hasn't been registered yet
            fireEvent(ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                    Map.of("entityName", "user", "fieldName", "nonexistent",
                            "categoryName", "PII"));

            // Should not crash; field should still not exist
            TenantContext.runWhere(TENANT_ID, () ->
                    assertNull(queryService.getField("nonexistent", "user")));
        }

        @Test
        @DisplayName("duplicate category assignment is idempotent")
        void duplicateCategoryIdempotent() throws Exception {
            fireEvent(ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "email"));
            fireEvent(ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "categoryNames", List.of("PII"))));
            fireEvent(ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "categoryNames", List.of("PII"))));

            TenantContext.runWhere(TENANT_ID, () -> {
                FieldView field = queryService.getField("email", "user");
                assertEquals(1, field.categories().size(), "Duplicate category should not be added");
            });
        }

        @Test
        @DisplayName("countSubjectConsentEntries returns -1 for unknown subject")
        void countUnknownSubject() {
            TenantContext.runWhere(TENANT_ID, () ->
                    assertEquals(-1, queryService.countSubjectConsentEntries("unknown")));
        }
    }
}
