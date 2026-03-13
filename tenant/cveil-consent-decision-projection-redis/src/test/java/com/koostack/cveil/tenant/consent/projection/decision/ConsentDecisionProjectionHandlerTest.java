package com.koostack.cveil.tenant.consent.projection.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.core.event.models.TenantEvent;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.cveil.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.cveil.core.tenant.consent.invalidation.InvalidationPublisher;
import com.koostack.cveil.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.cveil.plugin.consent.redis.ConsentPolicyRedisRepository.AffectedEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that {@link ConsentDecisionProjectionHandler} calls the correct repository methods and
 * publishes the correct {@link CacheInvalidationMessage} for each event type using the
 * Hash-per-subject schema.
 */
@DisplayName("ConsentDecisionProjectionHandler")
class ConsentDecisionProjectionHandlerTest {

    private static final String TENANT = "tenant-1";
    private static final String STREAM_KEY =
            "cveil:stream:shard:" + Math.floorMod("tenant-1".hashCode(), 8);
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
        assertDoesNotThrow(
                () ->
                        h.onEvent(
                                STREAM_KEY,
                                MSG_ID,
                                eventFields(
                                        ConsentEventTypes.CONSENT_GRANTED,
                                        """
            {"subjectId":"s1","purposeName":"p","policyType":"FIELD","fieldName":"f","entityName":"e"}
            """)));
        verifyNoInteractions(publisher);
    }

    // -----------------------------------------------------------------------
    // Policy events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CONSENT_GRANTED")
    class ConsentGranted {

        @Test
        @DisplayName("field-level grant calls setFieldConsent and publishes GRANTED")
        void fieldLevelGrant() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_GRANTED,
                            """
                {"subjectId":"user-1","purposeName":"marketing","policyType":"FIELD","fieldName":"email","entityName":"User"}
                """));

            verify(consentRepo).setFieldConsent("user-1", "User", "email", "marketing", true);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
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
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_GRANTED,
                            """
                {"subjectId":"user-1","purposeName":"marketing","policyType":"CATEGORY","categoryName":"PII"}
                """));

            verify(consentRepo).setCategoryConsent("user-1", "PII", "marketing", true);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
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
    @DisplayName("CONSENT_REVOKED")
    class ConsentRevoked {

        @Test
        @DisplayName("field-level revoke publishes REVOKED invalidation")
        void fieldLevelRevoke() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_REVOKED,
                            """
                {"subjectId":"user-1","purposeName":"analytics","policyType":"FIELD","fieldName":"ssn","entityName":"Person"}
                """));

            verify(consentRepo).setFieldConsent("user-1", "Person", "ssn", "analytics", false);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.REVOKED, captor.getValue().action());
            assertEquals("user-1", captor.getValue().subject());
        }

        @Test
        @DisplayName("category-level revoke publishes REVOKED invalidation")
        void categoryLevelRevoke() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_REVOKED,
                            """
                {"subjectId":"user-1","purposeName":"analytics","policyType":"CATEGORY","categoryName":"PII"}
                """));

            verify(consentRepo).setCategoryConsent("user-1", "PII", "analytics", false);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.REVOKED, captor.getValue().action());
            assertEquals("user-1", captor.getValue().subject());
            assertEquals("PII", captor.getValue().category());
        }
    }

    @Nested
    @DisplayName("CONSENT_ALL_REVOKED")
    class ConsentAllRevoked {

        @Test
        @DisplayName("calls removeAllConsent and publishes ALL_REVOKED")
        void allRevoked() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_ALL_REVOKED,
                            """
                {"subjectId":"user-1"}
                """));

            verify(consentRepo).removeAllConsent("user-1");

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            CacheInvalidationMessage msg = captor.getValue();
            assertEquals(InvalidationAction.ALL_REVOKED, msg.action());
            assertEquals("user-1", msg.subject());
            assertNull(msg.entity());
            assertNull(msg.field());
            assertNull(msg.purpose());
        }
    }

    @Nested
    @DisplayName("CONSENT_EXPIRED")
    class ConsentExpired {

        @Test
        @DisplayName("field-level expiry treated as revoke")
        void fieldLevelExpired() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_EXPIRED,
                            """
                {"subjectId":"user-1","purposeName":"marketing","policyType":"FIELD","fieldName":"email","entityName":"User"}
                """));

            verify(consentRepo).setFieldConsent("user-1", "User", "email", "marketing", false);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.REVOKED, captor.getValue().action());
            assertEquals("user-1", captor.getValue().subject());
        }

        @Test
        @DisplayName("category-level expiry treated as revoke")
        void categoryLevelExpired() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.CONSENT_EXPIRED,
                            """
                {"subjectId":"user-1","purposeName":"analytics","policyType":"CATEGORY","categoryName":"PII"}
                """));

            verify(consentRepo).setCategoryConsent("user-1", "PII", "analytics", false);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.REVOKED, captor.getValue().action());
            assertEquals("user-1", captor.getValue().subject());
            assertEquals("PII", captor.getValue().category());
        }
    }

    // -----------------------------------------------------------------------
    // Metadata events (publish-only)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FIELD_CATEGORY_ASSIGNED")
    class FieldCategoryAssigned {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED — no repo writes")
        void assigned() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                            """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """));

            verifyNoInteractions(consentRepo);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
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
    @DisplayName("FIELD_CATEGORY_UNASSIGNED")
    class FieldCategoryUnassigned {

        @Test
        @DisplayName("publishes CATEGORY_CHANGED on unassign — no repo writes")
        void unassigned() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
                            """
                {"entityName":"User","fieldName":"email","categoryName":"PII"}
                """));

            verifyNoInteractions(consentRepo);

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(captor.capture());

            assertEquals(InvalidationAction.CATEGORY_CHANGED, captor.getValue().action());
        }
    }

    // -----------------------------------------------------------------------
    // Structural deletion events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FIELD_REMOVED")
    class FieldRemoved {

        @Test
        @DisplayName("calls deleteByField, publishes per-entry REVOKED + broad FIELD_REMOVED")
        void removed() {
            when(consentRepo.deleteByField("User/email"))
                    .thenReturn(List.of(new AffectedEntry("alice", "marketing")));

            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.FIELD_REMOVED,
                            """
                {"entityName":"User","fieldName":"email"}
                """));

            verify(consentRepo).deleteByField("User/email");

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
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
    @DisplayName("PURPOSE_REMOVED")
    class PurposeRemoved {

        @Test
        @DisplayName("calls deleteByPurpose and publishes per-entry REVOKED")
        void purposeRemoved() {
            when(consentRepo.deleteByPurpose("marketing"))
                    .thenReturn(
                            List.of(
                                    new AffectedEntry("alice", "marketing"),
                                    new AffectedEntry("bob", "marketing")));

            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            ConsentEventTypes.PURPOSE_REMOVED,
                            """
                {"purposeName":"marketing"}
                """));

            verify(consentRepo).deleteByPurpose("marketing");

            ArgumentCaptor<CacheInvalidationMessage> captor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
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

            assertDoesNotThrow(
                    () ->
                            handler.onEvent(
                                    STREAM_KEY,
                                    MSG_ID,
                                    eventFields(
                                            ConsentEventTypes.CONSENT_GRANTED,
                                            """
                {"subjectId":"s1","purposeName":"p","policyType":"FIELD","fieldName":"f","entityName":"e"}
                """)));

            verify(consentRepo).setFieldConsent("s1", "e", "f", "p", true);
        }

        @Test
        @DisplayName("unknown event types are silently ignored (no publish)")
        void unknownEventIgnored() {
            handler.onEvent(
                    STREAM_KEY,
                    MSG_ID,
                    eventFields(
                            "consent.purpose.registered",
                            """
                {"purposeName":"marketing"}
                """));

            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("missing tenantId skips processing entirely")
        void missingTenantId() {
            Map<String, String> fields =
                    Map.of(
                            TenantEvent.FIELD_EVENT_TYPE,
                            ConsentEventTypes.CONSENT_GRANTED,
                            TenantEvent.FIELD_PAYLOAD,
                            "{}");

            handler.onEvent(STREAM_KEY, MSG_ID, fields);
            verifyNoInteractions(publisher);
            verifyNoInteractions(consentRepo);
        }

        @Test
        @DisplayName("malformed payload with missing required field gives clear error")
        void malformedPayloadMissingRequired() {
            RuntimeException ex =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    handler.onEvent(
                                            STREAM_KEY,
                                            MSG_ID,
                                            eventFields(
                                                    ConsentEventTypes.CONSENT_GRANTED,
                                                    """
                    {"purposeName":"p","policyType":"FIELD"}
                    """)));
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
                TenantEvent.FIELD_PAYLOAD, payload);
    }
}
