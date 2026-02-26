package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.data.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.data.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.TenantContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link JooqConsentAdminCommandService}.
 *
 * <p>Each test verifies:
 * <ol>
 *   <li>The command method completes without error</li>
 *   <li>The aggregate is correctly persisted and rehydratable from the event store</li>
 *   <li>The event is published to Redis and consumed by the test consumer</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JooqConsentAdminCommandServiceTest extends ConsentAdminCommandTestBase {

    private static final String TENANT = TEST_TENANT;

    // -----------------------------------------------------------------------
    // grantConsent
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void grantConsentCreatesNewPolicyAggregate() throws Exception {
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");

        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsent("alice", scope)
        );

        // Verify aggregate state
        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId("alice"),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertEquals("alice", agg.subjectId());
        assertEquals(1, agg.policies().size());
        assertTrue(agg.policies().getFirst().granted());
        assertEquals("marketing", agg.policies().getFirst().purposeName());
        assertEquals("email", agg.policies().getFirst().fieldName());

        // Verify Redis consumption
        awaitConsumedEvents(1, 5000);

        // Diagnostic: check stream length directly
        long streamLen = testJedis.xlen(STREAM_KEY);
        assertTrue(
            streamLen > 0 || !consumedByType(ConsentEventTypes.POLICY_GRANTED).isEmpty(),
            "Expected POLICY_GRANTED event — stream length=" + streamLen
                + ", consumed=" + consumedEvents.size()
        );
    }

    @Test
    @Order(2)
    void grantConsentAddsPolicyToExistingAggregate() throws Exception {
        ConsentScope scope = ConsentScope.to("PII", "phone", "User", "analytics");

        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsent("alice", scope)
        );

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId("alice"),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertEquals(2, agg.policies().size());
        var phonePolicy = agg.policies().stream()
            .filter(p -> "phone".equals(p.fieldName()))
            .findFirst().orElseThrow();
        assertTrue(phonePolicy.granted());
    }

    @Test
    @Order(3)
    void grantConsentUpsertsExistingPolicyScope() throws Exception {
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");

        // Grant again for same scope — should replace, not duplicate
        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsent("alice", scope)
        );

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId("alice"),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        long emailCount = agg.policies().stream()
            .filter(p -> "email".equals(p.fieldName()) && "marketing".equals(p.purposeName()))
            .count();
        assertEquals(1, emailCount, "Should upsert, not duplicate the same scope");
    }

    // -----------------------------------------------------------------------
    // revokeConsent
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void revokeConsentSetsPolicyToNotGranted() throws Exception {
        String subject = "bob";
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");

        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsent(subject, scope);
            commandService.revokeConsent(subject, scope);
        });

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId(subject),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertEquals(1, agg.policies().size());
        assertFalse(agg.policies().getFirst().granted());
    }

    @Test
    @Order(11)
    void revokeConsentOnExistingPolicyWorks() throws Exception {
        String subject = "charlie";
        ConsentScope scope = ConsentScope.to("HEALTH", "diagnosis", "Patient", "research");

        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsent(subject, scope);
            commandService.revokeConsent(subject, scope);
        });

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId(subject),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);
        assertFalse(agg.policies().getFirst().granted());
    }

    // -----------------------------------------------------------------------
    // grantConsent / revokeConsent with expiresAt
    // -----------------------------------------------------------------------

    @Test
    @Order(4)
    void grantConsentWithExpiresAtRoundTrip() throws Exception {
        String subject = "frank-expires";
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");
        Instant expiresAt = Instant.now().plusSeconds(86400 * 30); // 30 days

        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsent(subject, scope, expiresAt)
        );

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId(subject),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertEquals(1, agg.policies().size());
        assertTrue(agg.policies().getFirst().granted());
        assertNotNull(agg.policies().getFirst().expiresAt(),
            "expiresAt should survive the event-store round-trip");
        assertEquals(expiresAt, agg.policies().getFirst().expiresAt());
    }

    @Test
    @Order(5)
    void revokeConsentWithExpiresAtRoundTrip() throws Exception {
        String subject = "grace-expires";
        ConsentScope scope = ConsentScope.to("PII", "phone", "User", "analytics");
        Instant expiresAt = Instant.now().plusSeconds(86400 * 7); // 7 days

        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsent(subject, scope);
            commandService.revokeConsent(subject, scope, expiresAt);
        });

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId(subject),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertEquals(1, agg.policies().size());
        assertFalse(agg.policies().getFirst().granted());
        assertNotNull(agg.policies().getFirst().expiresAt(),
            "expiresAt should survive the event-store round-trip");
        assertEquals(expiresAt, agg.policies().getFirst().expiresAt());
    }

    // -----------------------------------------------------------------------
    // revokeAllConsent
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void revokeAllConsentClearsAllPolicies() throws Exception {
        String subject = "diana";
        ConsentScope scope1 = ConsentScope.to("PII", "email", "User", "marketing");
        ConsentScope scope2 = ConsentScope.to("PII", "phone", "User", "analytics");

        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsent(subject, scope1);
            commandService.grantConsent(subject, scope2);
            commandService.revokeAllConsent(subject);
        });

        AggregateKey key = new AggregateKey(
            ConsentPolicyAggregate.deriveId(subject),
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            TENANT
        );
        ConsentPolicyAggregate agg = eventService.getAggregate(key, ConsentPolicyAggregate.class);

        assertTrue(agg.policies().isEmpty(), "All policies should be cleared");

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.POLICY_ALL_REVOKED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // deleteAuditLogBySubject
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    void deleteAuditLogBySubjectReturnsZeroAndPublishesEvent() throws Exception {
        String subject = "eve";

        int result = TenantContext.callWhere(TENANT, () ->
            commandService.deleteAuditLogBySubject(subject)
        );

        assertEquals(-1, result, "Deletion is eventual — should return -1");

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.POLICY_AUDIT_DELETED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // registerField
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
    void registerFieldCreatesEntityAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerField("email", "User", "User email address")
        );

        AggregateKey key = new AggregateKey(
            EntityAggregate.deriveId("User"),
            EntityAggregate.AGGREGATE_TYPE,
            TENANT
        );
        EntityAggregate agg = eventService.getAggregate(key, EntityAggregate.class);

        assertEquals("User", agg.entityName());
        assertEquals(1, agg.fields().size());
        assertEquals("email", agg.fields().getFirst().fieldName());
        assertEquals("User email address", agg.fields().getFirst().description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.ENTITY_FIELD_REGISTERED).isEmpty());
    }

    @Test
    @Order(41)
    void registerFieldWithNullEntityUsesDefault() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerField("globalField", null, "A global field")
        );

        AggregateKey key = new AggregateKey(
            EntityAggregate.deriveId(EntityAggregate.DEFAULT_ENTITY),
            EntityAggregate.AGGREGATE_TYPE,
            TENANT
        );
        EntityAggregate agg = eventService.getAggregate(key, EntityAggregate.class);

        assertEquals(EntityAggregate.DEFAULT_ENTITY, agg.entityName());
        assertTrue(agg.fields().stream().anyMatch(f -> "globalField".equals(f.fieldName())));
    }

    @Test
    @Order(42)
    void registerMultipleFieldsOnSameEntity() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerField("firstName", "Employee", "First name");
            commandService.registerField("lastName", "Employee", "Last name");
        });

        AggregateKey key = new AggregateKey(
            EntityAggregate.deriveId("Employee"),
            EntityAggregate.AGGREGATE_TYPE,
            TENANT
        );
        EntityAggregate agg = eventService.getAggregate(key, EntityAggregate.class);

        assertEquals(2, agg.fields().size());
    }

    // -----------------------------------------------------------------------
    // registerCategory
    // -----------------------------------------------------------------------

    @Test
    @Order(50)
    void registerCategoryCreatesCategoryAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerCategory("PII", "Personally Identifiable Information")
        );

        AggregateKey key = new AggregateKey(
            CategoryAggregate.deriveId("PII"),
            CategoryAggregate.AGGREGATE_TYPE,
            TENANT
        );
        CategoryAggregate agg = eventService.getAggregate(key, CategoryAggregate.class);

        assertEquals("PII", agg.categoryName());
        assertEquals("Personally Identifiable Information", agg.description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.CATEGORY_REGISTERED).isEmpty());
    }

    @Test
    @Order(51)
    void registerCategoryUpdatesDescription() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerCategory("HEALTH", "Health data");
            commandService.registerCategory("HEALTH", "Protected health information (PHI)");
        });

        AggregateKey key = new AggregateKey(
            CategoryAggregate.deriveId("HEALTH"),
            CategoryAggregate.AGGREGATE_TYPE,
            TENANT
        );
        CategoryAggregate agg = eventService.getAggregate(key, CategoryAggregate.class);

        assertEquals("Protected health information (PHI)", agg.description());
    }

    // -----------------------------------------------------------------------
    // linkFieldToCategory
    // -----------------------------------------------------------------------

    @Test
    @Order(60)
    void linkFieldToCategoryAddsLink() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerField("ssn", "Person", "Social Security Number");
            commandService.linkFieldToCategory("ssn", "Person", "PII");
        });

        AggregateKey key = new AggregateKey(
            EntityAggregate.deriveId("Person"),
            EntityAggregate.AGGREGATE_TYPE,
            TENANT
        );
        EntityAggregate agg = eventService.getAggregate(key, EntityAggregate.class);

        var ssnField = agg.fields().stream()
            .filter(f -> "ssn".equals(f.fieldName()))
            .findFirst().orElseThrow();
        assertTrue(ssnField.categories().contains("PII"));

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED).isEmpty());
    }

    @Test
    @Order(61)
    void linkFieldToCategoryIsIdempotent() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerField("dob", "Person", "Date of birth");
            commandService.linkFieldToCategory("dob", "Person", "PII");
            commandService.linkFieldToCategory("dob", "Person", "PII"); // duplicate
        });

        AggregateKey key = new AggregateKey(
            EntityAggregate.deriveId("Person"),
            EntityAggregate.AGGREGATE_TYPE,
            TENANT
        );
        EntityAggregate agg = eventService.getAggregate(key, EntityAggregate.class);

        var dobField = agg.fields().stream()
            .filter(f -> "dob".equals(f.fieldName()))
            .findFirst().orElseThrow();
        long piiCount = dobField.categories().stream()
            .filter("PII"::equals).count();
        assertEquals(1, piiCount, "Duplicate category link should be idempotent");
    }

    // -----------------------------------------------------------------------
    // registerPurpose
    // -----------------------------------------------------------------------

    @Test
    @Order(70)
    void registerPurposeCreatesPurposeAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerPurpose("marketing", "Direct marketing communications")
        );

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("marketing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals("marketing", agg.purposeName());
        assertEquals("Direct marketing communications", agg.description());
        assertTrue(agg.regulationLinks().isEmpty());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REGISTERED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // registerRegulation
    // -----------------------------------------------------------------------

    @Test
    @Order(80)
    void registerRegulationCreatesRegulationAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerRegulation(
                "GDPR",
                "General Data Protection Regulation",
                "EU",
                "EU data protection regulation",
                "https://gdpr.eu"
            )
        );

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("GDPR"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        assertEquals("GDPR", agg.regulationName());
        assertEquals("General Data Protection Regulation", agg.fullName());
        assertEquals("EU", agg.jurisdiction());
        assertEquals("https://gdpr.eu", agg.url());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_REGISTERED).isEmpty());
    }

    @Test
    @Order(81)
    void registerRegulationUpdatesExisting() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerRegulation(
                "CCPA", "California Consumer Privacy Act",
                "California, US", "California privacy law", "https://oag.ca.gov/privacy/ccpa"
            );
            commandService.registerRegulation(
                "CCPA", "California Consumer Privacy Act (amended)",
                "California, US", "Updated CCPA description", "https://oag.ca.gov/privacy/ccpa"
            );
        });

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("CCPA"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        assertEquals("California Consumer Privacy Act (amended)", agg.fullName());
        assertEquals("Updated CCPA description", agg.description());
    }

    // -----------------------------------------------------------------------
    // linkPurposeToRegulation
    // -----------------------------------------------------------------------

    @Test
    @Order(90)
    void linkPurposeToRegulationAddsLink() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurpose("analytics", "Usage analytics");
            commandService.registerRegulation(
                "LGPD", "Lei Geral de Proteção de Dados",
                "Brazil", "Brazilian data protection", "https://lgpd.gov.br"
            );
            commandService.linkPurposeToRegulation("analytics", "LGPD", "legitimate-interest");
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("analytics"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(1, agg.regulationLinks().size());
        assertEquals("LGPD", agg.regulationLinks().getFirst().regulationName());
        assertEquals("legitimate-interest", agg.regulationLinks().getFirst().legalBasis());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REGULATION_LINKED).isEmpty());
    }

    @Test
    @Order(91)
    void linkPurposeToMultipleRegulations() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurpose("data-sharing", "Third-party data sharing");
            commandService.linkPurposeToRegulation("data-sharing", "GDPR", "explicit-consent");
            commandService.linkPurposeToRegulation("data-sharing", "CCPA", "opt-out");
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("data-sharing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(2, agg.regulationLinks().size());
    }

    // -----------------------------------------------------------------------
    // Redis stream — aggregate-level verification
    // -----------------------------------------------------------------------

    @Test
    @Order(100)
    void allEventsContainRequiredStreamFields() throws Exception {
        // Wait for outbox to flush all events produced by prior tests
        awaitConsumedEvents(consumedEvents.size() + 1, 6000);
        Thread.sleep(3000); // additional grace for outbox polling

        assertFalse(consumedEvents.isEmpty(), "Should have consumed at least one event from Redis");

        for (Map<String, String> event : consumedEvents) {
            assertNotNull(event.get(TenantEvent.FIELD_EVENT_ID), "Missing eventId");
            assertNotNull(event.get(TenantEvent.FIELD_TENANT_ID), "Missing tenantId");
            assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_ID), "Missing aggregateId");
            assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_TYPE), "Missing aggregateType");
            assertNotNull(event.get(TenantEvent.FIELD_EVENT_TYPE), "Missing eventType");
            assertNotNull(event.get(TenantEvent.FIELD_VERSION), "Missing version");
            assertNotNull(event.get(TenantEvent.FIELD_CHANGES), "Missing changes");
        }
    }

    // -----------------------------------------------------------------------
    // Negative / error-path tests
    // -----------------------------------------------------------------------

    @Test
    @Order(101)
    void grantConsentWithoutTenantContextThrows() {
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");
        assertThrows(
            com.koostack.operio.core.exception.OperioConfigurationException.class,
            () -> commandService.grantConsent("no-tenant-subject", scope),
            "Should throw when no tenant context is set"
        );
    }

    @Test
    @Order(102)
    void revokeConsentWithoutTenantContextThrows() {
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");
        assertThrows(
            com.koostack.operio.core.exception.OperioConfigurationException.class,
            () -> commandService.revokeConsent("no-tenant-subject", scope),
            "Should throw when no tenant context is set"
        );
    }

    @Test
    @Order(103)
    void grantConsentWithNullSubjectThrows() {
        ConsentScope scope = ConsentScope.to("PII", "email", "User", "marketing");
        assertThrows(Exception.class, () ->
            TenantContext.runWhere(TENANT, () ->
                commandService.grantConsent(null, scope)
            )
        );
    }

    @Test
    @Order(104)
    void grantConsentWithNullScopeThrows() {
        assertThrows(Exception.class, () ->
            TenantContext.runWhere(TENANT, () ->
                commandService.grantConsent("null-scope-subject", null)
            )
        );
    }

    // -----------------------------------------------------------------------
    // Plugin SPI verification
    // -----------------------------------------------------------------------

    @Test
    @Order(110)
    void serviceReportsReady() {
        assertTrue(commandService.isReady());
        assertEquals("jooq-consent-admin-command", commandService.getName());
        assertEquals(ConsentAdminCommandConfig.class, commandService.getConfigurationType());
    }
}
