package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JooqConsentAdminCommandService} — pure event
 * publishing with no orchestration or hydration.
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
        doAnswer(inv -> {
            publishedEvents.add(inv.getArgument(0));
            return null;
        }).when(eventService).publishEvent(any(PublishDomainEventInput.class));

        service = new JooqConsentAdminCommandService();
        service.configure(new ConsentAdminCommandConfig(eventService));
        publishedEvents.clear();
    }

    @Nested
    class GrantCommand {

        @Test
        void publishesSinglePolicyGrantedEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.grantConsentCommand("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void grantWithExpiresAtPublishesSingleEvent() {
            Instant expires = Instant.now().plusSeconds(3600);

            TenantContext.runWhere(TENANT, () ->
                service.grantConsentCommand("alice",
                    ConsentScope.forCategory("PII", "marketing"), expires)
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void categoryScopedGrantPublishesSingleEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.grantConsentCommand("alice",
                    ConsentScope.forCategory("PII", "marketing"))
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }
    }

    @Nested
    class RevokeCommand {

        @Test
        void publishesSinglePolicyRevokedEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.revokeConsentCommand("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_REVOKED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void revokeAllPublishesBulkEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.revokeAllConsentCommand("alice")
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_ALL_REVOKED,
                publishedEvents.get(0).eventType());
        }
    }

    @Nested
    class SchemaRegistrationCommands {

        @Test
        void registerFieldPublishesEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.registerFieldCommand("email", "User", "Email address")
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.ENTITY_FIELD_REGISTERED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void registerCategoryPublishesEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.registerCategoryCommand("PII", "Personal data")
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.CATEGORY_REGISTERED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void registerPurposePublishesEvent() {
            TenantContext.runWhere(TENANT, () ->
                service.registerPurposeCommand("marketing", "Marketing")
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_REGISTERED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void deleteAuditLogPublishesEvent() {
            TenantContext.runWhere(TENANT, () -> {
                int result = service.deleteAuditLogBySubjectCommand("alice");
                assertEquals(-1, result);
            });

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_AUDIT_DELETED,
                publishedEvents.get(0).eventType());
        }
    }
}
