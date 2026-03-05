package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConsentAdminRedisProjectionHandler")
class ConsentAdminRedisProjectionHandlerTest {

    private static final String TENANT_ID = "test-tenant";
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private ConsentAdminRedisRepository repository;
    private InvalidationPublisher publisher;
    private ConsentAdminRedisProjectionHandler handler;

    @BeforeEach
    void setUp() {
        repository = mock(ConsentAdminRedisRepository.class);
        publisher = mock(InvalidationPublisher.class);
        handler = new ConsentAdminRedisProjectionHandler(repository, publisher);
    }

    private Map<String, String> eventFields(String eventType, Object payload) throws Exception {
        return Map.of(
                TenantEvent.FIELD_TENANT_ID, TENANT_ID,
                TenantEvent.FIELD_EVENT_TYPE, eventType,
                TenantEvent.FIELD_PAYLOAD, mapper.writeValueAsString(payload)
        );
    }

    // ── Field Events ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Field Events")
    class FieldEvents {

        @Test
        @DisplayName("FIELD_REGISTERED saves new field and publishes invalidation")
        void fieldRegistered() throws Exception {
            Map<String, String> fields = eventFields(
                    ConsentEventTypes.FIELD_REGISTERED,
                    Map.of("entityName", "user", "fieldName", "email", "description", "User email"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<FieldView> captor = ArgumentCaptor.forClass(FieldView.class);
            verify(repository).saveField(captor.capture());
            FieldView saved = captor.getValue();
            assertEquals("email", saved.fieldName());
            assertEquals("user", saved.entity().entityName());
            assertEquals("User email", saved.description());
            assertTrue(saved.categories().isEmpty());

            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(msgCaptor.capture());
            assertEquals(InvalidationAction.FIELD_CHANGED, msgCaptor.getValue().action());
        }

        @Test
        @DisplayName("FIELD_REMOVED removes field from repository")
        void fieldRemoved() throws Exception {
            Map<String, String> fields = eventFields(
                    ConsentEventTypes.FIELD_REMOVED,
                    Map.of("entityName", "user", "fieldName", "email"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            verify(repository).removeField("user", "email");
            verify(publisher).publish(any(CacheInvalidationMessage.class));
        }

        @Test
        @DisplayName("FIELD_CATEGORY_ASSIGNED adds category to existing field")
        void fieldCategoryAssigned() throws Exception {
            FieldView existing = new FieldView(0L, "email", "desc",
                    new FieldView.EntityView(0L, "user"), List.of());
            when(repository.getField("email", "user")).thenReturn(existing);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "description", "desc", "categoryNames", List.of("PII"))));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<FieldView> captor = ArgumentCaptor.forClass(FieldView.class);
            verify(repository).saveField(captor.capture());
            assertEquals(1, captor.getValue().categories().size());
            assertEquals("PII", captor.getValue().categories().get(0).categoryName());
        }

        @Test
        @DisplayName("FIELD_CATEGORY_UNASSIGNED removes category from existing field")
        void fieldCategoryUnassigned() throws Exception {
            FieldView existing = new FieldView(0L, "email", "desc",
                    new FieldView.EntityView(0L, "user"),
                    List.of(new FieldView.CategoryView(0L, "PII"),
                            new FieldView.CategoryView(0L, "Sensitive")));
            when(repository.getField("email", "user")).thenReturn(existing);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
                    Map.of("entityName", "user", "fieldName", "email", "categoryName", "PII",
                            "snapshot", Map.of("fieldName", "email", "entityName", "user",
                                    "description", "desc", "categoryNames", List.of("Sensitive"))));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<FieldView> captor = ArgumentCaptor.forClass(FieldView.class);
            verify(repository).saveField(captor.capture());
            assertEquals(1, captor.getValue().categories().size());
            assertEquals("Sensitive", captor.getValue().categories().get(0).categoryName());
        }
    }

    // ── Purpose Events ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Purpose Events")
    class PurposeEvents {

        @Test
        @DisplayName("PURPOSE_REGISTERED saves new purpose")
        void purposeRegistered() throws Exception {
            Map<String, String> fields = eventFields(
                    ConsentEventTypes.PURPOSE_REGISTERED,
                    Map.of("purposeName", "marketing", "description", "Marketing purpose"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<PurposeView> captor = ArgumentCaptor.forClass(PurposeView.class);
            verify(repository).savePurpose(captor.capture());
            assertEquals("marketing", captor.getValue().purposeName());
            assertEquals("Marketing purpose", captor.getValue().description());

            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(msgCaptor.capture());
            assertEquals(InvalidationAction.PURPOSE_CHANGED, msgCaptor.getValue().action());
        }

        @Test
        @DisplayName("PURPOSE_REMOVED removes purpose from repository")
        void purposeRemoved() throws Exception {
            Map<String, String> fields = eventFields(
                    ConsentEventTypes.PURPOSE_REMOVED,
                    Map.of("purposeName", "marketing"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            verify(repository).removePurpose("marketing");
        }

        @Test
        @DisplayName("PURPOSE_REGULATION_LINKED adds regulation to existing purpose")
        void purposeRegulationLinked() throws Exception {
            PurposeView existing = new PurposeView(0L, "marketing", "desc", List.of(), List.of());
            when(repository.getPurpose("marketing")).thenReturn(existing);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.PURPOSE_REGULATION_LINKED,
                    Map.of("purposeName", "marketing", "regulationName", "GDPR",
                            "lawfulBasis", "CONSENT",
                            "snapshot", Map.of("purposeName", "marketing", "description", "desc",
                                    "regulations", List.of(Map.of("regulationName", "GDPR", "lawfulBasis", "CONSENT")),
                                    "policies", List.of())));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<PurposeView> captor = ArgumentCaptor.forClass(PurposeView.class);
            verify(repository).savePurpose(captor.capture());
            assertEquals(1, captor.getValue().regulations().size());
            assertEquals("GDPR", captor.getValue().regulations().get(0).regulationName());
        }
    }

    // ── Regulation Events ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Regulation Events")
    class RegulationEvents {

        @Test
        @DisplayName("REGULATION_REGISTERED saves new regulation")
        void regulationRegistered() throws Exception {
            Map<String, String> fields = eventFields(
                    ConsentEventTypes.REGULATION_REGISTERED,
                    Map.of("regulationName", "GDPR", "description", "EU regulation"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<RegulationView> captor = ArgumentCaptor.forClass(RegulationView.class);
            verify(repository).saveRegulation(captor.capture());
            assertEquals("GDPR", captor.getValue().regulationName());

            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(msgCaptor.capture());
            assertEquals(InvalidationAction.REGULATION_CHANGED, msgCaptor.getValue().action());
        }

        @Test
        @DisplayName("REGULATION_JURISDICTION_ADDED adds jurisdiction")
        void regulationJurisdictionAdded() throws Exception {
            RegulationView existing = new RegulationView(0L, "GDPR", "desc", List.of());
            when(repository.getRegulation("GDPR")).thenReturn(existing);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.REGULATION_JURISDICTION_ADDED,
                    Map.of("regulationName", "GDPR", "jurisdictionName", "EU",
                            "snapshot", Map.of("regulationName", "GDPR", "description", "desc",
                                    "jurisdictionNames", List.of("EU"))));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<RegulationView> captor = ArgumentCaptor.forClass(RegulationView.class);
            verify(repository).saveRegulation(captor.capture());
            assertEquals(1, captor.getValue().activeJurisdictions().size());
            assertEquals("EU", captor.getValue().activeJurisdictions().get(0).jurisdictionName());
        }
    }

    // ── Consent Events ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SubjectConsent Events")
    class ConsentEvents {

        @Test
        @DisplayName("CONSENT_GRANTED creates/updates consent entry")
        void consentGranted() throws Exception {
            when(repository.getSubjectConsent("sub-1")).thenReturn(null);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.CONSENT_GRANTED,
                    Map.of("subjectId", "sub-1", "policyId", 42,
                            "action", "GRANT", "purposeName", "marketing",
                            "policyType", "FIELD"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<SubjectConsentView> captor = ArgumentCaptor.forClass(SubjectConsentView.class);
            verify(repository).saveSubjectConsent(captor.capture());
            assertEquals("sub-1", captor.getValue().subjectId());
            assertEquals(1, captor.getValue().entries().size());
            assertEquals(42L, captor.getValue().entries().get(0).policyId());

            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);
            verify(publisher).publish(msgCaptor.capture());
            assertEquals(InvalidationAction.CONSENT_ENTRY_CHANGED, msgCaptor.getValue().action());
        }

        @Test
        @DisplayName("CONSENT_ALL_REVOKED marks all entries as REVOKE")
        void consentAllRevoked() throws Exception {
            SubjectConsentView existing = new SubjectConsentView("sub-1", List.of(
                    new SubjectConsentView.ConsentLedgerEntryView(
                            1L, "sub-1", 42L, "marketing", "FIELD",
                            "email", "user", null, "GRANT", "admin",
                            null, null, java.time.Instant.now(),
                            null)));
            when(repository.getSubjectConsent("sub-1")).thenReturn(existing);

            Map<String, String> fields = eventFields(
                    ConsentEventTypes.CONSENT_ALL_REVOKED,
                    Map.of("subjectId", "sub-1", "actor", "admin"));

            TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

            ArgumentCaptor<SubjectConsentView> captor = ArgumentCaptor.forClass(SubjectConsentView.class);
            verify(repository).saveSubjectConsent(captor.capture());
            assertEquals("REVOKE", captor.getValue().entries().get(0).action());
        }
    }

    // ── Edge Cases ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("events with missing tenantId are skipped")
    void missingTenantIdSkipped() {
        Map<String, String> fields = Map.of(
                TenantEvent.FIELD_EVENT_TYPE, ConsentEventTypes.FIELD_REGISTERED,
                TenantEvent.FIELD_PAYLOAD, "{}");

        handler.onEvent("stream-1", "msg-1", fields);

        verifyNoInteractions(repository, publisher);
    }

    @Test
    @DisplayName("unknown event types are silently ignored")
    void unknownEventIgnored() throws Exception {
        Map<String, String> fields = eventFields("UNKNOWN_EVENT_TYPE", Map.of());

        TenantContext.runWhere(TENANT_ID, () -> handler.onEvent("stream-1", "msg-1", fields));

        verifyNoInteractions(repository);
    }
}
