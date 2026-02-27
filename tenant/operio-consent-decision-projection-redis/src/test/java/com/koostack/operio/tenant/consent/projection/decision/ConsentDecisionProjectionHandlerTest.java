package com.koostack.operio.tenant.consent.projection.decision;

import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.data.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.FieldCategoryMappingRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link ConsentDecisionProjectionHandler} publishes the correct
 * {@link CacheInvalidationMessage} for each event type.
 */
@DisplayName("ConsentDecisionProjectionHandler")
class ConsentDecisionProjectionHandlerTest {

    private static final String TENANT = "tenant-1";
    private static final String STREAM_KEY = "operio:stream:domain:0";
    private static final String MSG_ID = "1-0";

    private ConsentPolicyRedisRepository consentRepo;
    private FieldCategoryMappingRedisRepository mappingRepo;
    private InvalidationPublisher publisher;
    private ConsentDecisionProjectionHandler handler;

    @BeforeEach
    void setUp() {
        consentRepo = mock(ConsentPolicyRedisRepository.class);
        mappingRepo = mock(FieldCategoryMappingRedisRepository.class);
        publisher = mock(InvalidationPublisher.class);
        handler = new ConsentDecisionProjectionHandler(consentRepo, mappingRepo, publisher);
    }

    // -----------------------------------------------------------------------
    // Backwards compatibility
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two-arg constructor still works (no publisher)")
    void twoArgConstructor() {
        ConsentDecisionProjectionHandler h = new ConsentDecisionProjectionHandler(consentRepo, mappingRepo);
        // Should not throw even without a publisher
        assertDoesNotThrow(() -> h.onEvent(STREAM_KEY, MSG_ID, eventFields(
            ConsentEventTypes.POLICY_GRANTED,
            """
            {"subjectId":"s1","purposeName":"p","fieldName":"f","entityName":"e","granted":true}
            """
        )));
        verifyNoInteractions(publisher);
    }

    // -----------------------------------------------------------------------
    // Policy events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("POLICY_GRANTED")
    class PolicyGranted {

        @Test
        @DisplayName("field-level grant publishes GRANTED invalidation")
        void fieldLevelGrant() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","fieldName":"email","entityName":"User","granted":true}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(TENANT, msg.tenantId());
            assertEquals("user-1", msg.subject());
            assertEquals("User", msg.entity());
            assertEquals("email", msg.field());
            assertEquals("marketing", msg.purpose());
            assertEquals(InvalidationAction.GRANTED, msg.action());
            assertNotNull(msg.messageId());
            assertNull(msg.category());
        }

        @Test
        @DisplayName("category-level grant batch-publishes one message per fan-out field")
        void categoryLevelGrant() {
            when(mappingRepo.getFieldsForCategory("PII")).thenReturn(Set.of("User/email", "User/phone"));

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","category":"PII","granted":true}
                """
            ));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CacheInvalidationMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(publisher).publishBatch(captor.capture());

            List<CacheInvalidationMessage> batch = captor.getValue();
            assertEquals(2, batch.size());
            assertTrue(batch.stream().allMatch(m -> m.action() == InvalidationAction.GRANTED));
            assertTrue(batch.stream().allMatch(m -> m.subject().equals("user-1")));
            assertTrue(batch.stream().allMatch(m -> m.category().equals("PII")));
        }

        @Test
        @DisplayName("empty category fan-out still publishes invalidation for stale-metadata clients")
        void emptyCategoryFanOutStillPublishes() {
            when(mappingRepo.getFieldsForCategory("PII")).thenReturn(Set.of());

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","category":"PII","granted":true}
                """
            ));

            // No cache write should have happened
            verifyNoInteractions(consentRepo);

            // But an invalidation should still be published
            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.GRANTED, msg.action());
            assertEquals("user-1", msg.subject());
            assertEquals("PII", msg.category());
            assertNull(msg.entity());
            assertNull(msg.field());
        }
    }

    @Nested
    @DisplayName("POLICY_REVOKED")
    class PolicyRevoked {

        @Test
        @DisplayName("field-level revoke publishes REVOKED invalidation")
        void fieldLevelRevoke() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_REVOKED,
                """
                {"subjectId":"user-1","purposeName":"analytics","fieldName":"ssn","entityName":"Person","granted":false}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.REVOKED, captor.getValue().action());
            assertEquals("user-1", captor.getValue().subject());
        }
    }

    @Nested
    @DisplayName("POLICY_ALL_REVOKED")
    class PolicyAllRevoked {

        @Test
        @DisplayName("publishes ALL_REVOKED with subject only")
        void allRevoked() {
            when(consentRepo.deleteAllCachedKeysForSubject("user-1")).thenReturn(5L);

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_ALL_REVOKED,
                """
                {"subjectId":"user-1"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.ALL_REVOKED, msg.action());
            assertEquals("user-1", msg.subject());
            assertNull(msg.entity());
            assertNull(msg.field());
            assertNull(msg.purpose());
        }
    }

    // -----------------------------------------------------------------------
    // Metadata events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ENTITY_FIELD_CATEGORY_LINKED")
    class FieldCategoryLinked {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED with entity, field, and category")
        void linked() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
                """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.CATEGORY_CHANGED, msg.action());
            assertNull(msg.subject());
            assertEquals("User", msg.entity());
            assertEquals("email", msg.field());
            assertEquals("PII", msg.category());
        }
    }

    @Nested
    @DisplayName("ENTITY_FIELD_CATEGORY_UNLINKED")
    class FieldCategoryUnlinked {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED on unlink")
        void unlinked() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED,
                """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.CATEGORY_CHANGED, captor.getValue().action());
        }
    }

    @Nested
    @DisplayName("CATEGORY_DELETED")
    class CategoryDeleted {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED with category only")
        void deleted() {
            when(mappingRepo.removeAllMappingsForCategory("PII")).thenReturn(3);

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.CATEGORY_DELETED,
                """
                {"categoryName":"PII"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.CATEGORY_CHANGED, msg.action());
            assertEquals("PII", msg.category());
            assertNull(msg.subject());
            assertNull(msg.entity());
            assertNull(msg.field());
        }
    }

    @Nested
    @DisplayName("ENTITY_FIELD_REMOVED")
    class EntityFieldRemoved {

        @Test
        @DisplayName("publishes FIELD_REMOVED with entity and field")
        void removed() {
            when(mappingRepo.removeAllMappingsForField("User", "email")).thenReturn(2);

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_REMOVED,
                """
                {"entityName":"User","fieldName":"email"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.FIELD_REMOVED, msg.action());
            assertEquals("User", msg.entity());
            assertEquals("email", msg.field());
            assertNull(msg.subject());
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("publisher failure does not propagate to event processing")
        void publisherFailureDoesNotPropagate() {
            doThrow(new RuntimeException("Redis down")).when(publisher).publish(any());

            assertDoesNotThrow(() -> handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"s1","purposeName":"p","fieldName":"f","entityName":"e","granted":true}
                """
            )));

            // Cache write should still have happened
            verify(consentRepo).setFieldConsent("s1", "e", "f", "p", true);
        }

        @Test
        @DisplayName("unknown event types are silently ignored (no publish)")
        void unknownEventIgnored() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                "consent.purpose.registered",
                """
                {"purposeName":"marketing"}
                """
            ));

            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("missing tenantId skips processing entirely")
        void missingTenantId() {
            Map<String, String> fields = Map.of(
                TenantEvent.FIELD_EVENT_TYPE, ConsentEventTypes.POLICY_GRANTED,
                TenantEvent.FIELD_PAYLOAD, "{}"
            );

            handler.onEvent(STREAM_KEY, MSG_ID, fields);
            verifyNoInteractions(publisher);
            verifyNoInteractions(consentRepo);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map<String, String> eventFields(String eventType, String payload) {
        return Map.of(
            TenantEvent.FIELD_TENANT_ID, TENANT,
            TenantEvent.FIELD_EVENT_TYPE, eventType,
            TenantEvent.FIELD_PAYLOAD, payload
        );
    }
}
