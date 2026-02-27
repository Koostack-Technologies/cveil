package com.koostack.operio.plugin.consent.jooq.hydration;

import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.data.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.data.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.tenant.event.jooq.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the JOOQ hydration repository implementations.
 */
class JooqHydrationRepositoryTest {

    private static final String TENANT = "test-tenant";

    private TenantEventService<?> eventService;
    private EventRepository eventRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        eventService = mock(TenantEventService.class);
        eventRepository = mock(EventRepository.class);
    }

    @Nested
    class EntityHydration {

        private JooqEntityHydrationRepository repo;

        @BeforeEach
        void setUp() {
            repo = new JooqEntityHydrationRepository(eventService, eventRepository);
        }

        @Test
        void findByNameDerivesCorrectKey() throws Exception {
            var expected = EntityAggregate.create(TENANT, "User");
            UUID expectedId = EntityAggregate.deriveId("User");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, EntityAggregate.AGGREGATE_TYPE, TENANT)),
                eq(EntityAggregate.class)
            )).thenReturn(expected);

            EntityAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName("User"));

            assertNotNull(result);
            assertEquals("User", result.entityName());
        }

        @Test
        void findByNameNormalizesNull() throws Exception {
            UUID defaultId = EntityAggregate.deriveId(EntityAggregate.DEFAULT_ENTITY);

            when(eventService.getAggregate(
                eq(new AggregateKey(defaultId, EntityAggregate.AGGREGATE_TYPE, TENANT)),
                eq(EntityAggregate.class)
            )).thenReturn(null);

            EntityAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName(null));

            assertNull(result);
            verify(eventService).getAggregate(
                eq(new AggregateKey(defaultId, EntityAggregate.AGGREGATE_TYPE, TENANT)),
                eq(EntityAggregate.class)
            );
        }

        @Test
        void listEntityAggregateIdsDelegatesToEventRepo() throws Exception {
            var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
            when(eventRepository.listAggregateIds(EntityAggregate.AGGREGATE_TYPE, TENANT))
                .thenReturn(ids);

            List<UUID> result = TenantContext.callWhere(TENANT,
                () -> repo.listEntityAggregateIds());

            assertEquals(ids, result);
        }
    }

    @Nested
    class CategoryHydration {

        @Test
        void findByNameDerivesCorrectKey() throws Exception {
            var repo = new JooqCategoryHydrationRepository(eventService);
            var expected = CategoryAggregate.create(TENANT, "PII", "PII data");
            UUID expectedId = CategoryAggregate.deriveId("PII");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, CategoryAggregate.AGGREGATE_TYPE, TENANT)),
                eq(CategoryAggregate.class)
            )).thenReturn(expected);

            CategoryAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName("PII"));

            assertNotNull(result);
            assertEquals("PII", result.categoryName());
        }
    }

    @Nested
    class PurposeHydration {

        @Test
        void findByNameDerivesCorrectKey() throws Exception {
            var repo = new JooqPurposeHydrationRepository(eventService);
            var expected = PurposeAggregate.create(TENANT, "marketing", "Marketing");
            UUID expectedId = PurposeAggregate.deriveId("marketing");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, PurposeAggregate.AGGREGATE_TYPE, TENANT)),
                eq(PurposeAggregate.class)
            )).thenReturn(expected);

            PurposeAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName("marketing"));

            assertNotNull(result);
            assertEquals("marketing", result.purposeName());
        }

        @Test
        void findByNameReturnsNullForMissing() throws Exception {
            var repo = new JooqPurposeHydrationRepository(eventService);
            UUID expectedId = PurposeAggregate.deriveId("unknown");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, PurposeAggregate.AGGREGATE_TYPE, TENANT)),
                eq(PurposeAggregate.class)
            )).thenReturn(null);

            PurposeAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName("unknown"));

            assertNull(result);
        }
    }

    @Nested
    class ConsentPolicyHydration {

        @Test
        void findBySubjectDerivesCorrectKey() throws Exception {
            var repo = new JooqConsentPolicyHydrationRepository(eventService);
            var expected = ConsentPolicyAggregate.create(TENANT, "alice");
            UUID expectedId = ConsentPolicyAggregate.deriveId("alice");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, ConsentPolicyAggregate.AGGREGATE_TYPE, TENANT)),
                eq(ConsentPolicyAggregate.class)
            )).thenReturn(expected);

            ConsentPolicyAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findBySubject("alice"));

            assertNotNull(result);
            assertEquals("alice", result.subjectId());
        }
    }

    @Nested
    class RegulationHydration {

        @Test
        void findByNameDerivesCorrectKey() throws Exception {
            var repo = new JooqRegulationHydrationRepository(eventService);
            var expected = RegulationAggregate.create(
                TENANT, "GDPR", "General Data Protection Regulation",
                "EU", "EU regulation", "https://gdpr.eu"
            );
            UUID expectedId = RegulationAggregate.deriveId("GDPR");

            when(eventService.getAggregate(
                eq(new AggregateKey(expectedId, RegulationAggregate.AGGREGATE_TYPE, TENANT)),
                eq(RegulationAggregate.class)
            )).thenReturn(expected);

            RegulationAggregate result = TenantContext.callWhere(TENANT,
                () -> repo.findByName("GDPR"));

            assertNotNull(result);
            assertEquals("GDPR", result.regulationName());
        }
    }
}
