package com.koostack.cveil.core.tenant.consent.invalidation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies JSON serialization round-trip for {@link CacheInvalidationMessage}. */
@DisplayName("CacheInvalidationMessage")
class CacheInvalidationMessageTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("round-trips a fully-populated message through JSON")
    void fullRoundTrip() throws Exception {
        CacheInvalidationMessage original =
                new CacheInvalidationMessage(
                        "msg-001",
                        "tenant-a",
                        "subject-1",
                        "User",
                        "email",
                        "PII",
                        "marketing",
                        InvalidationAction.GRANTED);

        String json = mapper.writeValueAsString(original);
        CacheInvalidationMessage deserialized =
                mapper.readValue(json, CacheInvalidationMessage.class);

        assertEquals(original, deserialized);
    }

    @Test
    @DisplayName("round-trips a message with null optional fields")
    void nullFieldsRoundTrip() throws Exception {
        CacheInvalidationMessage original =
                new CacheInvalidationMessage(
                        "msg-002",
                        "tenant-b",
                        null,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.ALL_REVOKED);

        String json = mapper.writeValueAsString(original);
        CacheInvalidationMessage deserialized =
                mapper.readValue(json, CacheInvalidationMessage.class);

        assertEquals(original, deserialized);
        assertNull(deserialized.subject());
        assertNull(deserialized.entity());
        assertNull(deserialized.field());
        assertNull(deserialized.category());
        assertNull(deserialized.purpose());
    }

    @Test
    @DisplayName("@JsonInclude(NON_NULL) omits null fields from JSON output")
    void nullFieldsOmittedInJson() throws Exception {
        CacheInvalidationMessage msg =
                new CacheInvalidationMessage(
                        "msg-003",
                        "tenant-c",
                        "subj",
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.ALL_REVOKED);

        String json = mapper.writeValueAsString(msg);
        assertFalse(json.contains("\"entity\""), "null entity should be omitted");
        assertFalse(json.contains("\"field\""), "null field should be omitted");
        assertFalse(json.contains("\"category\""), "null category should be omitted");
        assertFalse(json.contains("\"purpose\""), "null purpose should be omitted");
        assertTrue(json.contains("\"messageId\""), "messageId should be present");
        assertTrue(json.contains("\"tenantId\""), "tenantId should be present");
        assertTrue(json.contains("\"subject\""), "non-null subject should be present");
    }

    @Test
    @DisplayName("all InvalidationAction values serialize and deserialize")
    void allActionsRoundTrip() throws Exception {
        for (InvalidationAction action : InvalidationAction.values()) {
            CacheInvalidationMessage msg =
                    new CacheInvalidationMessage(
                            "msg-" + action.name(), "t", null, null, null, null, null, action);
            String json = mapper.writeValueAsString(msg);
            CacheInvalidationMessage deserialized =
                    mapper.readValue(json, CacheInvalidationMessage.class);
            assertEquals(action, deserialized.action());
        }
    }
}
