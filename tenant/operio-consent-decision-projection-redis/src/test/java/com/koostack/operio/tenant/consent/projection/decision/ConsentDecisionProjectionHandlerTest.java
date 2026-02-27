package com.koostack.operio.tenant.consent.projection.decision;

import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.data.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository.AffectedEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link ConsentDecisionProjectionHandler} calls the correct
 * repository methods and publishes the correct {@link CacheInvalidationMessage}
 * for each event type using the Hash-per-subject schema.
 */
@DisplayName("ConsentDecisionProjectionHandler")
class ConsentDecisionProjectionHandlerTest {

    private static final String TENANT = "tenant-1";
    private static final String STREAM_KEY = "operio:stream:domain:0";
    private static final String MSG_ID = "1-0";

    private ConsentPolicyRedisRepository consentRepo;
    private InvalidationPublisher publisher;
    private ConsentDecisionProjectionHandler handler;

    @BeforeEach
    void setUp() {
        consentRepo = mock(ConsentPolicyRedisRepository.class);
        publisher = mock(InvalidationPublisher.class);
        handler = new ConsentDecisionProjectionHandler(consentRepo, publisher);
    }

    // -----------------------------------------------------------------------
    // Backwards compatibility
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one-arg constructor still works (no publisher)")
    void oneArgConstructor() {
        ConsentDecisionProjectionHandler h = new ConsentDecisionProjectionHandler(consentRepo);
        assertDoesNotThrow(() -> h.onEvent(STREAM_KEY, MSG_ID, eventFields(
            ConsentEventTypes.POLICY_GRANTED,
            """
            {"subjectId":"s1","purposeName":"p","fieldName":"f","entityName":"e","category":"*","granted":true}
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
        @DisplayName("field-level grant calls setFieldConsent and publishes GRANTED")
        void fieldLevelGrant() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","fieldName":"email","entityName":"User","category":"*","granted":true}
                """
            ));

            verify(consentRepo).setFieldConsent("user-1", "User", "email", "marketing", true);

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
        @DisplayName("category-level grant calls setCategoryConsent and publishes GRANTED")
        void categoryLevelGrant() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","fieldName":"*","entityName":"*","category":"PII","granted":true}
                """
            ));

            verify(consentRepo).setCategoryConsent("user-1", "PII", "marketing", true);

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.GRANTED, msg.action());
            assertEquals("user-1", msg.subject());
            assertEquals("PII", msg.category());
            assertNull(msg.entity());
            assertNull(msg.field());
        }

        @Test
        @DisplayName("backwards-compatible: null fieldName/entityName treated as wildcard (category-level)")
        void nullFieldsTreatedAsWildcard() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_GRANTED,
                """
                {"subjectId":"user-1","purposeName":"marketing","category":"PII","granted":true}
                """
            ));

            verify(consentRepo).setCategoryConsent("user-1", "PII", "marketing", true);
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
                {"subjectId":"user-1","purposeName":"analytics","fieldName":"ssn","entityName":"Person","category":"*","granted":false}
                """
            ));

            verify(consentRepo).setFieldConsent("user-1", "Person", "ssn", "analytics", false);

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
        @DisplayName("calls removeAllConsent and publishes ALL_REVOKED")
        void allRevoked() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.POLICY_ALL_REVOKED,
                """
                {"subjectId":"user-1"}
                """
            ));

            verify(consentRepo).removeAllConsent("user-1");

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
    // Metadata events (publish-only)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ENTITY_FIELD_CATEGORY_LINKED")
    class FieldCategoryLinked {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED — no repo writes")
        void linked() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
                """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """
            ));

            verifyNoInteractions(consentRepo);

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
        @DisplayName("publishes CATEGORY_CHANGED on unlink — no repo writes")
        void unlinked() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED,
                """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """
            ));

            verifyNoInteractions(consentRepo);

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.CATEGORY_CHANGED, captor.getValue().action());
        }
    }

    // -----------------------------------------------------------------------
    // Structural deletion events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CATEGORY_DELETED")
    class CategoryDeleted {

        @Test
        @DisplayName("calls deleteByCategory, publishes per-entry REVOKED + broad CATEGORY_CHANGED")
        void deleted() {
            when(consentRepo.deleteByCategory("PII")).thenReturn(List.of(
                new AffectedEntry("alice", "marketing"),
                new AffectedEntry("bob", "analytics")
            ));

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.CATEGORY_DELETED,
                """
                {"categoryName":"PII"}
                """
            ));

            verify(consentRepo).deleteByCategory("PII");

            // 2 per-entry + 1 broad = 3 publishes
            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher, times(3)).publish(captor.capture());

            List<CacheInvalidationMessage> msgs = captor.getAllValues();
            // Per-entry messages
            assertEquals(InvalidationAction.REVOKED, msgs.get(0).action());
            assertEquals("alice", msgs.get(0).subject());
            assertEquals("PII", msgs.get(0).category());
            assertEquals("marketing", msgs.get(0).purpose());

            assertEquals(InvalidationAction.REVOKED, msgs.get(1).action());
            assertEquals("bob", msgs.get(1).subject());

            // Broad notification
            assertEquals(InvalidationAction.CATEGORY_CHANGED, msgs.get(2).action());
            assertEquals("PII", msgs.get(2).category());
            assertNull(msgs.get(2).subject());
        }

        @Test
        @DisplayName("no affected entries still publishes broad CATEGORY_CHANGED")
        void noAffected() {
            when(consentRepo.deleteByCategory("EMPTY")).thenReturn(List.of());

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.CATEGORY_DELETED,
                """
                {"categoryName":"EMPTY"}
                """
            ));

            // Only broad notification
            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher, times(1)).publish(captor.capture());
            assertEquals(InvalidationAction.CATEGORY_CHANGED, captor.getValue().action());
        }
    }

    @Nested
    @DisplayName("ENTITY_FIELD_REMOVED")
    class EntityFieldRemoved {

        @Test
        @DisplayName("calls deleteByField, publishes per-entry REVOKED + broad FIELD_REMOVED")
        void removed() {
            when(consentRepo.deleteByField("User/email")).thenReturn(List.of(
                new AffectedEntry("alice", "marketing")
            ));

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_FIELD_REMOVED,
                """
                {"entityName":"User","fieldName":"email"}
                """
            ));

            verify(consentRepo).deleteByField("User/email");

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher, times(2)).publish(captor.capture());

            List<CacheInvalidationMessage> msgs = captor.getAllValues();
            assertEquals(InvalidationAction.REVOKED, msgs.get(0).action());
            assertEquals("alice", msgs.get(0).subject());
            assertEquals("User", msgs.get(0).entity());
            assertEquals("email", msgs.get(0).field());

            assertEquals(InvalidationAction.FIELD_REMOVED, msgs.get(1).action());
            assertEquals("User", msgs.get(1).entity());
            assertEquals("email", msgs.get(1).field());
            assertNull(msgs.get(1).subject());
        }
    }

    @Nested
    @DisplayName("ENTITY_DELETED")
    class EntityDeleted {

        @Test
        @DisplayName("publishes broad FIELD_REMOVED for entity — cleanup deferred to per-field events")
        void entityDeleted() {
            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.ENTITY_DELETED,
                """
                {"entityName":"User"}
                """
            ));

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.FIELD_REMOVED, msg.action());
            assertEquals("User", msg.entity());
            assertNull(msg.subject());
            assertNull(msg.field());
        }
    }

    @Nested
    @DisplayName("PURPOSE_DELETED")
    class PurposeDeleted {

        @Test
        @DisplayName("calls deleteByPurpose and publishes per-entry REVOKED")
        void purposeDeleted() {
            when(consentRepo.deleteByPurpose("marketing")).thenReturn(List.of(
                new AffectedEntry("alice", "marketing"),
                new AffectedEntry("bob", "marketing")
            ));

            handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                ConsentEventTypes.PURPOSE_DELETED,
                """
                {"purposeName":"marketing"}
                """
            ));

            verify(consentRepo).deleteByPurpose("marketing");

            ArgumentCaptor<CacheInvalidationMessage> captor = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher, times(2)).publish(captor.capture());

            List<CacheInvalidationMessage> msgs = captor.getAllValues();
            assertEquals(InvalidationAction.REVOKED, msgs.get(0).action());
            assertEquals("alice", msgs.get(0).subject());
            assertEquals("marketing", msgs.get(0).purpose());

            assertEquals(InvalidationAction.REVOKED, msgs.get(1).action());
            assertEquals("bob", msgs.get(1).subject());
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
                {"subjectId":"s1","purposeName":"p","fieldName":"f","entityName":"e","category":"*","granted":true}
                """
            )));

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

        @Test
        @DisplayName("malformed payload with missing required field gives clear error")
        void malformedPayloadMissingRequired() {
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                handler.onEvent(STREAM_KEY, MSG_ID, eventFields(
                    ConsentEventTypes.POLICY_GRANTED,
                    """
                    {"purposeName":"p","granted":true}
                    """
                )));
            assertTrue(ex.getCause() instanceof IllegalArgumentException);
            assertTrue(ex.getCause().getMessage().contains("subjectId"));
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
