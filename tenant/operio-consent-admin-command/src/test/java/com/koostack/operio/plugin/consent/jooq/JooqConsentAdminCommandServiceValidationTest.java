package com.koostack.operio.plugin.consent.jooq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JooqConsentAdminCommandService} — validates that commands publish the
 * correct events and that null/blank required arguments are rejected.
 */
class JooqConsentAdminCommandServiceValidationTest {

    private static final String TENANT = "test-tenant";

    private EventService<?> eventService;
    private JooqConsentAdminCommandService service;

    private final List<PublishDomainEventInput> publishedEvents = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        eventService = mock(EventService.class);

        when(eventService.isReady()).thenReturn(true);
        doAnswer(
                        inv -> {
                            publishedEvents.add(inv.getArgument(0));
                            return null;
                        })
                .when(eventService)
                .publishEvent(any(PublishDomainEventInput.class));

        service = new JooqConsentAdminCommandService();
        service.configure(new ConsentAdminCommandConfig(eventService));
        publishedEvents.clear();
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    @Nested
    class RegisterFieldCommand {

        @Test
        void publishesFieldRegisteredEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.registerFieldCommand(
                                    "email", "User", "Email address", "User entity"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.FIELD_REGISTERED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullFieldNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.registerFieldCommand(
                                                    null, "User", "desc", "entity desc")));
        }
    }

    @Nested
    class UpdateFieldCommand {

        @Test
        void publishesFieldUpdatedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () -> service.updateFieldCommand("email", "User", "Updated description", null));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.FIELD_UPDATED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullFieldNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () -> service.updateFieldCommand(null, "User", "desc", null)));
        }
    }

    @Nested
    class RemoveFieldCommand {

        @Test
        void publishesFieldRemovedEvent() {
            TenantContext.runWhere(TENANT, () -> service.removeFieldCommand("email", "User"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.FIELD_REMOVED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullFieldNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT, () -> service.removeFieldCommand(null, "User")));
        }
    }

    @Nested
    class AssignFieldCategoryCommand {

        @Test
        void publishesFieldCategoryAssignedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.assignFieldCategoryCommand(
                                    "email", "User", "PII", "Personal data", null));

            assertEquals(1, publishedEvents.size());
            assertEquals(
                    ConsentEventTypes.FIELD_CATEGORY_ASSIGNED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullFieldNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.assignFieldCategoryCommand(
                                                    null, "User", "PII", "desc", null)));
        }

        @Test
        void nullCategoryNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.assignFieldCategoryCommand(
                                                    "email", "User", null, "desc", null)));
        }
    }

    @Nested
    class UnassignFieldCategoryCommand {

        @Test
        void publishesFieldCategoryUnassignedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () -> service.unassignFieldCategoryCommand("email", "User", "PII", null));

            assertEquals(1, publishedEvents.size());
            assertEquals(
                    ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
                    publishedEvents.get(0).eventType());
        }

        @Test
        void nullFieldNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.unassignFieldCategoryCommand(
                                                    null, "User", "PII", null)));
        }

        @Test
        void nullCategoryNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.unassignFieldCategoryCommand(
                                                    "email", "User", null, null)));
        }
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    @Nested
    class RegisterPurposeCommand {

        @Test
        void publishesPurposeRegisteredEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () -> service.registerPurposeCommand("marketing", "Marketing purposes"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_REGISTERED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT, () -> service.registerPurposeCommand(null, "desc")));
        }
    }

    @Nested
    class UpdatePurposeCommand {

        @Test
        void publishesPurposeUpdatedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () -> service.updatePurposeCommand("marketing", "Updated description", null));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_UPDATED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () -> service.updatePurposeCommand(null, "desc", null)));
        }
    }

    @Nested
    class RemovePurposeCommand {

        @Test
        void publishesPurposeRemovedEvent() {
            TenantContext.runWhere(TENANT, () -> service.removePurposeCommand("marketing"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_REMOVED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> TenantContext.runWhere(TENANT, () -> service.removePurposeCommand(null)));
        }
    }

    @Nested
    class LinkPurposeToRegulationCommand {

        @Test
        void publishesPurposeRegulationLinkedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.linkPurposeToRegulationCommand(
                                    "marketing",
                                    "GDPR",
                                    "consent",
                                    "PLAINTEXT",
                                    "REDACT_ALL",
                                    null));

            assertEquals(1, publishedEvents.size());
            assertEquals(
                    ConsentEventTypes.PURPOSE_REGULATION_LINKED,
                    publishedEvents.get(0).eventType());
        }

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.linkPurposeToRegulationCommand(
                                                    null, "GDPR", "consent", null, null, null)));
        }

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.linkPurposeToRegulationCommand(
                                                    "marketing",
                                                    null,
                                                    "consent",
                                                    null,
                                                    null,
                                                    null)));
        }
    }

    @Nested
    class UnlinkPurposeFromRegulationCommand {

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.unlinkPurposeFromRegulationCommand(
                                                    null, "GDPR", null)));
        }

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.unlinkPurposeFromRegulationCommand(
                                                    "marketing", null, null)));
        }
    }

    @Nested
    class AddPurposePolicyCommand {

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.addPurposePolicyCommand(
                                                    null, "FIELD", "email", "User", null, null)));
        }

        @Test
        void nullPolicyTypeThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.addPurposePolicyCommand(
                                                    "marketing",
                                                    null,
                                                    "email",
                                                    "User",
                                                    null,
                                                    null)));
        }
    }

    @Nested
    class RemovePurposePolicyCommand {

        @Test
        void nullPurposeNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.removePurposePolicyCommand(
                                                    null, "FIELD", "email", "User", null, null)));
        }

        @Test
        void nullPolicyTypeThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.removePurposePolicyCommand(
                                                    "marketing",
                                                    null,
                                                    "email",
                                                    "User",
                                                    null,
                                                    null)));
        }
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    @Nested
    class RegisterRegulationCommand {

        @Test
        void publishesRegulationRegisteredEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.registerRegulationCommand(
                                    "GDPR", "General Data Protection Regulation"));

            assertEquals(1, publishedEvents.size());
            assertEquals(
                    ConsentEventTypes.REGULATION_REGISTERED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT, () -> service.registerRegulationCommand(null, "desc")));
        }
    }

    @Nested
    class UpdateRegulationCommand {

        @Test
        void publishesRegulationUpdatedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () -> service.updateRegulationCommand("GDPR", "Updated description", null));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.REGULATION_UPDATED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () -> service.updateRegulationCommand(null, "desc", null)));
        }
    }

    @Nested
    class RemoveRegulationCommand {

        @Test
        void publishesRegulationRemovedEvent() {
            TenantContext.runWhere(TENANT, () -> service.removeRegulationCommand("GDPR"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.REGULATION_REMOVED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT, () -> service.removeRegulationCommand(null)));
        }
    }

    @Nested
    class AddRegulationJurisdictionCommand {

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.addRegulationJurisdictionCommand(
                                                    null, "EU", "European Union", null)));
        }

        @Test
        void nullJurisdictionNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.addRegulationJurisdictionCommand(
                                                    "GDPR", null, "desc", null)));
        }
    }

    @Nested
    class RemoveRegulationJurisdictionCommand {

        @Test
        void nullRegulationNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.removeRegulationJurisdictionCommand(
                                                    null, "EU", null)));
        }

        @Test
        void nullJurisdictionNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.removeRegulationJurisdictionCommand(
                                                    "GDPR", null, null)));
        }
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate
    // -----------------------------------------------------------------------

    @Nested
    class GrantConsentCommand {

        @Test
        void publishesConsentGrantedEvent() {
            Instant expires = Instant.now().plusSeconds(3600);

            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.grantConsentCommand(
                                    "alice",
                                    1L,
                                    "marketing",
                                    "FIELD",
                                    "email",
                                    "User",
                                    "PII",
                                    "admin",
                                    "127.0.0.1",
                                    "TestAgent",
                                    expires));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.CONSENT_GRANTED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullSubjectIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.grantConsentCommand(
                                                    null,
                                                    1L,
                                                    "marketing",
                                                    "FIELD",
                                                    "email",
                                                    "User",
                                                    "PII",
                                                    "admin",
                                                    "127.0.0.1",
                                                    "TestAgent",
                                                    null)));
        }
    }

    @Nested
    class RevokeConsentCommand {

        @Test
        void publishesConsentRevokedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.revokeConsentCommand(
                                    "alice",
                                    1L,
                                    "marketing",
                                    "FIELD",
                                    "email",
                                    "User",
                                    "PII",
                                    "admin",
                                    "127.0.0.1",
                                    "TestAgent"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.CONSENT_REVOKED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullSubjectIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.revokeConsentCommand(
                                                    null,
                                                    1L,
                                                    "marketing",
                                                    "FIELD",
                                                    "email",
                                                    "User",
                                                    "PII",
                                                    "admin",
                                                    "127.0.0.1",
                                                    "TestAgent")));
        }
    }

    @Nested
    class RevokeAllConsentCommand {

        @Test
        void publishesConsentAllRevokedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.revokeAllConsentCommand(
                                    "alice", "admin", "127.0.0.1", "TestAgent"));

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.CONSENT_ALL_REVOKED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullSubjectIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.revokeAllConsentCommand(
                                                    null, "admin", "127.0.0.1", "TestAgent")));
        }
    }

    // -----------------------------------------------------------------------
    // DSAR Aggregate
    // -----------------------------------------------------------------------

    @Nested
    class DeleteSubjectAuditCommand {

        @Test
        void publishesSubjectAuditDeletedEvent() {
            TenantContext.runWhere(
                    TENANT,
                    () ->
                            service.deleteSubjectAuditCommand(
                                    "alice", "req-001", "admin", "127.0.0.1", "TestAgent"));

            assertEquals(1, publishedEvents.size());
            assertEquals(
                    ConsentEventTypes.SUBJECT_AUDIT_DELETED, publishedEvents.get(0).eventType());
        }

        @Test
        void nullSubjectIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.deleteSubjectAuditCommand(
                                                    null,
                                                    "req-001",
                                                    "admin",
                                                    "127.0.0.1",
                                                    "TestAgent")));
        }

        @Test
        void nullDeletionRequestIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            TenantContext.runWhere(
                                    TENANT,
                                    () ->
                                            service.deleteSubjectAuditCommand(
                                                    "alice",
                                                    null,
                                                    "admin",
                                                    "127.0.0.1",
                                                    "TestAgent")));
        }
    }
}
