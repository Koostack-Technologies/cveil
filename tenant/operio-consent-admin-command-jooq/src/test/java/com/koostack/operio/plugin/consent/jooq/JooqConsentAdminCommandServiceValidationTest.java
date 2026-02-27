package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.tenant.consent.hydration.EntityHydrationRepository;
import com.koostack.operio.core.tenant.consent.hydration.PurposeHydrationRepository;
import com.koostack.operio.core.event.TenantEventService;
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
 * Unit tests for the validation + auto-create logic added to
 * {@link JooqConsentAdminCommandService} in Phase 2.
 */
class JooqConsentAdminCommandServiceValidationTest {

    private static final String TENANT = "test-tenant";

    private TenantEventService<?> eventService;
    private EntityHydrationRepository entityRepo;
    private CategoryHydrationRepository categoryRepo;
    private PurposeHydrationRepository purposeRepo;
    private JooqConsentAdminCommandService service;

    private final List<PublishDomainEventInput> publishedEvents = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        eventService = mock(TenantEventService.class);
        entityRepo = mock(EntityHydrationRepository.class);
        categoryRepo = mock(CategoryHydrationRepository.class);
        purposeRepo = mock(PurposeHydrationRepository.class);

        when(eventService.isReady()).thenReturn(true);
        doAnswer(inv -> {
            publishedEvents.add(inv.getArgument(0));
            return null;
        }).when(eventService).publishEvent(any(PublishDomainEventInput.class));

        service = new JooqConsentAdminCommandService();
        service.configure(new ConsentAdminCommandConfig(
            eventService, entityRepo, categoryRepo, purposeRepo
        ));
        publishedEvents.clear();
    }

    // -----------------------------------------------------------------------
    // Grant — field-scoped
    // -----------------------------------------------------------------------

    @Nested
    class GrantFieldScoped {

        @Test
        void existingEntitiesNoAutoCreate() {
            var entity = EntityAggregate.create(TENANT, "User")
                .withField("email", "Email address");
            var purpose = PurposeAggregate.create(TENANT, "marketing", "Marketing");

            when(entityRepo.findByName("User")).thenReturn(entity);
            when(purposeRepo.findByName("marketing")).thenReturn(purpose);

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            // Only the POLICY_GRANTED event should be published (no auto-create events)
            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void missingPurposeAutoCreates() {
            var entity = EntityAggregate.create(TENANT, "User")
                .withField("email", "Email");

            when(entityRepo.findByName("User")).thenReturn(entity);
            when(purposeRepo.findByName("marketing")).thenReturn(null);

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            // PURPOSE_REGISTERED + POLICY_GRANTED
            assertEquals(2, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_REGISTERED,
                publishedEvents.get(0).eventType());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(1).eventType());
        }

        @Test
        void missingEntityAndFieldAutoCreates() {
            when(entityRepo.findByName("User")).thenReturn(null);
            when(purposeRepo.findByName("marketing"))
                .thenReturn(PurposeAggregate.create(TENANT, "marketing", null));

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            // ENTITY_FIELD_REGISTERED + POLICY_GRANTED
            assertEquals(2, publishedEvents.size());
            assertEquals(ConsentEventTypes.ENTITY_FIELD_REGISTERED,
                publishedEvents.get(0).eventType());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(1).eventType());
        }

        @Test
        void existingEntityMissingFieldAutoCreatesField() {
            var entity = EntityAggregate.create(TENANT, "User"); // no fields
            when(entityRepo.findByName("User")).thenReturn(entity);
            when(purposeRepo.findByName("analytics"))
                .thenReturn(PurposeAggregate.create(TENANT, "analytics", null));

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("bob",
                    ConsentScope.forField("phone", "User", "analytics"))
            );

            // ENTITY_FIELD_REGISTERED + POLICY_GRANTED
            assertEquals(2, publishedEvents.size());
            assertEquals(ConsentEventTypes.ENTITY_FIELD_REGISTERED,
                publishedEvents.get(0).eventType());
        }
    }

    // -----------------------------------------------------------------------
    // Grant — category-scoped
    // -----------------------------------------------------------------------

    @Nested
    class GrantCategoryScoped {

        @Test
        void existingCategoryNoAutoCreate() {
            when(categoryRepo.findByName("PII"))
                .thenReturn(CategoryAggregate.create(TENANT, "PII", "PII data"));
            when(purposeRepo.findByName("marketing"))
                .thenReturn(PurposeAggregate.create(TENANT, "marketing", null));

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forCategory("PII", "marketing"))
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }

        @Test
        void missingCategoryAutoCreates() {
            when(categoryRepo.findByName("HEALTH")).thenReturn(null);
            when(purposeRepo.findByName("research"))
                .thenReturn(PurposeAggregate.create(TENANT, "research", null));

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forCategory("HEALTH", "research"))
            );

            // CATEGORY_REGISTERED + POLICY_GRANTED
            assertEquals(2, publishedEvents.size());
            assertEquals(ConsentEventTypes.CATEGORY_REGISTERED,
                publishedEvents.get(0).eventType());
        }
    }

    // -----------------------------------------------------------------------
    // Revoke — same validation applies
    // -----------------------------------------------------------------------

    @Nested
    class RevokeValidation {

        @Test
        void revokeWithMissingPurposeAutoCreates() {
            var entity = EntityAggregate.create(TENANT, "User")
                .withField("email", "Email");

            when(entityRepo.findByName("User")).thenReturn(entity);
            when(purposeRepo.findByName("marketing")).thenReturn(null);

            TenantContext.runWhere(TENANT, () ->
                service.revokeConsent("alice",
                    ConsentScope.forField("email", "User", "marketing"))
            );

            assertEquals(2, publishedEvents.size());
            assertEquals(ConsentEventTypes.PURPOSE_REGISTERED,
                publishedEvents.get(0).eventType());
            assertEquals(ConsentEventTypes.POLICY_REVOKED,
                publishedEvents.get(1).eventType());
        }
    }

    // -----------------------------------------------------------------------
    // Grant with expiresAt
    // -----------------------------------------------------------------------

    @Nested
    class GrantWithExpiration {

        @Test
        void grantWithExpiresAtIncludesExpiration() {
            when(purposeRepo.findByName("marketing"))
                .thenReturn(PurposeAggregate.create(TENANT, "marketing", null));
            when(categoryRepo.findByName("PII"))
                .thenReturn(CategoryAggregate.create(TENANT, "PII", null));

            Instant expires = Instant.now().plusSeconds(3600);

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forCategory("PII", "marketing"), expires)
            );

            assertEquals(1, publishedEvents.size());
            assertEquals(ConsentEventTypes.POLICY_GRANTED,
                publishedEvents.get(0).eventType());
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases — wildcard / null guards
    // -----------------------------------------------------------------------

    @Nested
    class WildcardAndNullGuards {

        @Test
        void wildcardEntityNameSkipsEntityValidation() {
            when(purposeRepo.findByName("marketing"))
                .thenReturn(PurposeAggregate.create(TENANT, "marketing", null));

            // forField with wildcard entity — should NOT try to look up entity "*"
            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice",
                    ConsentScope.forField("email", "*", "marketing"))
            );

            verify(entityRepo, never()).findByName(any());
            assertEquals(1, publishedEvents.size());
        }

        @Test
        void wildcardFieldNameSkipsFieldValidation() {
            when(purposeRepo.findByName("marketing"))
                .thenReturn(PurposeAggregate.create(TENANT, "marketing", null));
            when(categoryRepo.findByName("PII"))
                .thenReturn(CategoryAggregate.create(TENANT, "PII", null));

            // Scope with both category and wildcard field — category validation runs,
            // entity/field validation should be skipped
            var scope = new ConsentScope("PII", "*", "User", "marketing");
            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice", scope)
            );

            verify(entityRepo, never()).findByName(any());
            assertEquals(1, publishedEvents.size());
        }

        @Test
        void deprecatedToFactoryWithBothDimensionsSkipsValidation() {
            // to("PII", "email", "marketing") creates (PII, email, null, marketing)
            // Neither isCategoryScoped (fieldName=email ≠ wildcard) nor
            // isFieldScoped (category=PII ≠ wildcard) — validation silently skipped
            @SuppressWarnings("deprecation")
            var scope = ConsentScope.to("PII", "email", "marketing");

            TenantContext.runWhere(TENANT, () ->
                service.grantConsent("alice", scope)
            );

            verify(entityRepo, never()).findByName(any());
            verify(categoryRepo, never()).findByName(any());
            // Only purpose is always checked when non-null
            verify(purposeRepo).findByName("marketing");
        }
    }
}
