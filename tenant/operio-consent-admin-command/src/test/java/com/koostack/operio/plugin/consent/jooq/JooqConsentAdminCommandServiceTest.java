package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectConsentAggregate;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateKey;
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
    // Field Aggregate — register, update, remove, category assign/unassign
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void registerFieldCreatesFieldAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerFieldCommand("email", "User", "User email address", "User entity")
        );

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("User", "email"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals("email", agg.fieldName());
        assertEquals("User", agg.entityName());
        assertEquals("User email address", agg.description());
        assertEquals("User entity", agg.entityDescription());
        assertTrue(agg.categories().isEmpty());

        awaitConsumedEvents(1, 5000);
        long streamLen = testJedis.xlen(STREAM_KEY);
        assertTrue(
            streamLen > 0 || !consumedByType(ConsentEventTypes.FIELD_REGISTERED).isEmpty(),
            "Expected FIELD_REGISTERED event — stream length=" + streamLen
                + ", consumed=" + consumedEvents.size()
        );
    }

    @Test
    @Order(2)
    void registerFieldWithNullEntityUsesDefault() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerFieldCommand("globalField", null, "A global field", null)
        );

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId(null, "globalField"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals(FieldAggregate.DEFAULT_ENTITY, agg.entityName());
        assertEquals("globalField", agg.fieldName());
    }

    @Test
    @Order(3)
    void updateFieldChangesDescription() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("phone", "User", "Phone number", "User entity");
            commandService.updateFieldCommand("phone", "User", "Mobile phone number");
        });

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("User", "phone"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals("Mobile phone number", agg.description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.FIELD_UPDATED).isEmpty());
    }

    @Test
    @Order(4)
    void removeFieldPublishesEvent() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("tempField", "TempEntity", "Temporary", "Temp");
            commandService.removeFieldCommand("tempField", "TempEntity");
        });

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.FIELD_REMOVED).isEmpty());
    }

    @Test
    @Order(5)
    void assignFieldCategoryAddsCategory() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("ssn", "Person", "Social Security Number", "Person entity");
            commandService.assignFieldCategoryCommand("ssn", "Person", "PII", "Personally Identifiable Information");
        });

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("Person", "ssn"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals(1, agg.categories().size());
        assertEquals("PII", agg.categories().getFirst().categoryName());
        assertEquals("Personally Identifiable Information", agg.categories().getFirst().categoryDescription());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.FIELD_CATEGORY_ASSIGNED).isEmpty());
    }

    @Test
    @Order(6)
    void assignFieldCategoryIsIdempotent() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("dob", "Person", "Date of birth", "Person entity");
            commandService.assignFieldCategoryCommand("dob", "Person", "PII", "PII data");
            commandService.assignFieldCategoryCommand("dob", "Person", "PII", "PII data"); // duplicate
        });

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("Person", "dob"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        long piiCount = agg.categories().stream()
            .filter(c -> "PII".equals(c.categoryName())).count();
        assertEquals(1, piiCount, "Duplicate category assignment should be idempotent");
    }

    @Test
    @Order(7)
    void assignMultipleCategoriesToField() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("diagnosis", "Patient", "Medical diagnosis", "Patient entity");
            commandService.assignFieldCategoryCommand("diagnosis", "Patient", "HEALTH", "Health data");
            commandService.assignFieldCategoryCommand("diagnosis", "Patient", "SENSITIVE", "Sensitive data");
        });

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("Patient", "diagnosis"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals(2, agg.categories().size());
    }

    @Test
    @Order(8)
    void unassignFieldCategoryRemovesCategory() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerFieldCommand("address", "User", "Mailing address", "User entity");
            commandService.assignFieldCategoryCommand("address", "User", "PII", "PII data");
            commandService.assignFieldCategoryCommand("address", "User", "CONTACT", "Contact info");
            commandService.unassignFieldCategoryCommand("address", "User", "PII");
        });

        AggregateKey key = new AggregateKey(
            FieldAggregate.deriveId("User", "address"),
            FieldAggregate.AGGREGATE_TYPE,
            TENANT
        );
        FieldAggregate agg = eventService.getAggregate(key, FieldAggregate.class);

        assertEquals(1, agg.categories().size());
        assertEquals("CONTACT", agg.categories().getFirst().categoryName());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate — register, update, remove, regulation link/unlink,
    //                      policy add/remove
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void registerPurposeCreatesPurposeAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerPurposeCommand("marketing", "Direct marketing communications")
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
        assertTrue(agg.policies().isEmpty());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REGISTERED).isEmpty());
    }

    @Test
    @Order(11)
    void updatePurposeChangesDescription() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurposeCommand("analytics", "Usage analytics");
            commandService.updatePurposeCommand("analytics", "Advanced usage analytics and reporting");
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("analytics"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals("Advanced usage analytics and reporting", agg.description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_UPDATED).isEmpty());
    }

    @Test
    @Order(12)
    void removePurposePublishesEvent() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurposeCommand("temp-purpose", "Temporary purpose");
            commandService.removePurposeCommand("temp-purpose");
        });

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REMOVED).isEmpty());
    }

    @Test
    @Order(13)
    void linkPurposeToRegulationAddsLink() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.linkPurposeToRegulationCommand(
                "marketing", "GDPR", "explicit-consent", "allow-processing", "block-processing"
            )
        );

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("marketing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(1, agg.regulationLinks().size());
        var link = agg.regulationLinks().getFirst();
        assertEquals("GDPR", link.regulationName());
        assertEquals("explicit-consent", link.lawfulBasis());
        assertEquals("allow-processing", link.onGranted());
        assertEquals("block-processing", link.onDenied());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REGULATION_LINKED).isEmpty());
    }

    @Test
    @Order(14)
    void linkPurposeToMultipleRegulations() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurposeCommand("data-sharing", "Third-party data sharing");
            commandService.linkPurposeToRegulationCommand(
                "data-sharing", "GDPR", "explicit-consent", "share", "block"
            );
            commandService.linkPurposeToRegulationCommand(
                "data-sharing", "CCPA", "opt-out", "share", "stop-sharing"
            );
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("data-sharing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(2, agg.regulationLinks().size());
    }

    @Test
    @Order(15)
    void unlinkPurposeFromRegulationRemovesLink() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerPurposeCommand("profiling", "User profiling");
            commandService.linkPurposeToRegulationCommand(
                "profiling", "GDPR", "legitimate-interest", "profile", "anonymize"
            );
            commandService.linkPurposeToRegulationCommand(
                "profiling", "CCPA", "opt-out", "profile", "stop"
            );
            commandService.unlinkPurposeFromRegulationCommand("profiling", "GDPR");
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("profiling"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(1, agg.regulationLinks().size());
        assertEquals("CCPA", agg.regulationLinks().getFirst().regulationName());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_REGULATION_UNLINKED).isEmpty());
    }

    @Test
    @Order(16)
    void addPurposePolicyAddsPolicy() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.addPurposePolicyCommand(
                "marketing", "field", "email", "User", "PII"
            )
        );

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("marketing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        assertEquals(1, agg.policies().size());
        var policy = agg.policies().getFirst();
        assertEquals("field", policy.policyType());
        assertEquals("email", policy.fieldName());
        assertEquals("User", policy.entityName());
        assertEquals("PII", policy.categoryName());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_POLICY_ADDED).isEmpty());
    }

    @Test
    @Order(17)
    void removePurposePolicyRemovesPolicy() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.addPurposePolicyCommand(
                "marketing", "field", "phone", "User", "PII"
            );
            commandService.removePurposePolicyCommand(
                "marketing", "field", "phone", "User", "PII"
            );
        });

        AggregateKey key = new AggregateKey(
            PurposeAggregate.deriveId("marketing"),
            PurposeAggregate.AGGREGATE_TYPE,
            TENANT
        );
        PurposeAggregate agg = eventService.getAggregate(key, PurposeAggregate.class);

        boolean hasPhonePolicy = agg.policies().stream()
            .anyMatch(p -> "phone".equals(p.fieldName()));
        assertFalse(hasPhonePolicy, "Phone policy should have been removed");

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.PURPOSE_POLICY_REMOVED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate — register, update, remove, jurisdiction add/remove
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void registerRegulationCreatesRegulationAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.registerRegulationCommand("GDPR", "General Data Protection Regulation")
        );

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("GDPR"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        assertEquals("GDPR", agg.regulationName());
        assertEquals("General Data Protection Regulation", agg.description());
        assertTrue(agg.jurisdictions().isEmpty());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_REGISTERED).isEmpty());
    }

    @Test
    @Order(21)
    void updateRegulationChangesDescription() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerRegulationCommand("CCPA", "California Consumer Privacy Act");
            commandService.updateRegulationCommand("CCPA", "California Consumer Privacy Act (amended)");
        });

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("CCPA"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        assertEquals("California Consumer Privacy Act (amended)", agg.description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_UPDATED).isEmpty());
    }

    @Test
    @Order(22)
    void removeRegulationPublishesEvent() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.registerRegulationCommand("TEMP_REG", "Temporary regulation");
            commandService.removeRegulationCommand("TEMP_REG");
        });

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_REMOVED).isEmpty());
    }

    @Test
    @Order(23)
    void addRegulationJurisdictionAddsJurisdiction() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.addRegulationJurisdictionCommand("GDPR", "EU", "European Union")
        );

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("GDPR"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        assertEquals(1, agg.jurisdictions().size());
        var jurisdiction = agg.jurisdictions().getFirst();
        assertEquals("EU", jurisdiction.jurisdictionName());
        assertEquals("European Union", jurisdiction.description());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_JURISDICTION_ADDED).isEmpty());
    }

    @Test
    @Order(24)
    void addMultipleJurisdictionsToRegulation() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.addRegulationJurisdictionCommand("GDPR", "EEA", "European Economic Area");
            commandService.addRegulationJurisdictionCommand("GDPR", "UK", "United Kingdom");
        });

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("GDPR"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        // EU from Order(23) + EEA + UK = 3
        assertEquals(3, agg.jurisdictions().size());
    }

    @Test
    @Order(25)
    void removeRegulationJurisdictionRemovesJurisdiction() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.removeRegulationJurisdictionCommand("GDPR", "EEA")
        );

        AggregateKey key = new AggregateKey(
            RegulationAggregate.deriveId("GDPR"),
            RegulationAggregate.AGGREGATE_TYPE,
            TENANT
        );
        RegulationAggregate agg = eventService.getAggregate(key, RegulationAggregate.class);

        // EU + UK remain (EEA removed)
        assertEquals(2, agg.jurisdictions().size());
        assertTrue(agg.jurisdictions().stream().noneMatch(j -> "EEA".equals(j.jurisdictionName())));

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.REGULATION_JURISDICTION_REMOVED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate — grant, revoke, expire, revoke-all
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    void grantConsentCreatesSubjectConsentAggregate() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(86400 * 30);

        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsentCommand(
                "alice", 1001L, "marketing", "field", "email", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0", expiresAt
            )
        );

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("alice"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        assertEquals("alice", agg.subjectId());
        assertEquals(1, agg.entries().size());
        var entry = agg.entries().getFirst();
        assertEquals(1001L, entry.policyId());
        assertEquals("marketing", entry.purposeName());
        assertEquals("field", entry.policyType());
        assertEquals("email", entry.fieldName());
        assertEquals("User", entry.entityName());
        assertEquals("PII", entry.categoryName());
        assertEquals("GRANT", entry.action());
        assertEquals("admin", entry.actor());
        assertEquals("192.168.1.1", entry.ipAddress());
        assertEquals("TestAgent/1.0", entry.userAgent());
        assertEquals(expiresAt, entry.expiresAt());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.CONSENT_GRANTED).isEmpty());
    }

    @Test
    @Order(31)
    void grantConsentAddsEntryToExistingAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsentCommand(
                "alice", 1002L, "analytics", "field", "phone", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0", null
            )
        );

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("alice"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        assertEquals(2, agg.entries().size());
        var phoneEntry = agg.entries().stream()
            .filter(e -> e.policyId() == 1002L)
            .findFirst().orElseThrow();
        assertEquals("GRANT", phoneEntry.action());
        assertEquals("phone", phoneEntry.fieldName());
    }

    @Test
    @Order(32)
    void grantConsentUpsertsByPolicyId() throws Exception {
        Instant newExpiry = Instant.now().plusSeconds(86400 * 60);

        TenantContext.runWhere(TENANT, () ->
            commandService.grantConsentCommand(
                "alice", 1001L, "marketing", "field", "email", "User", "PII",
                "admin", "10.0.0.1", "TestAgent/2.0", newExpiry
            )
        );

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("alice"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        long count = agg.entries().stream().filter(e -> e.policyId() == 1001L).count();
        assertEquals(1, count, "Should upsert, not duplicate the same policyId");
    }

    @Test
    @Order(33)
    void revokeConsentSetsEntryToRevoke() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsentCommand(
                "bob", 2001L, "marketing", "field", "email", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0", null
            );
            commandService.revokeConsentCommand(
                "bob", 2001L, "marketing", "field", "email", "User", "PII",
                "admin", "192.168.1.2", "TestAgent/1.0"
            );
        });

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("bob"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        assertEquals(1, agg.entries().size());
        assertEquals("REVOKE", agg.entries().getFirst().action());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.CONSENT_REVOKED).isEmpty());
    }

    @Test
    @Order(34)
    void expireConsentSetsEntryToExpire() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsentCommand(
                "charlie", 3001L, "analytics", "field", "phone", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0",
                Instant.now().minusSeconds(1) // already expired
            );
            commandService.expireConsentCommand(
                "charlie", 3001L, "analytics", "field", "phone", "User", "PII"
            );
        });

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("charlie"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        assertEquals(1, agg.entries().size());
        assertEquals("EXPIRE", agg.entries().getFirst().action());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.CONSENT_EXPIRED).isEmpty());
    }

    @Test
    @Order(35)
    void revokeAllConsentRevokesAllEntries() throws Exception {
        TenantContext.runWhere(TENANT, () -> {
            commandService.grantConsentCommand(
                "diana", 4001L, "marketing", "field", "email", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0", null
            );
            commandService.grantConsentCommand(
                "diana", 4002L, "analytics", "field", "phone", "User", "PII",
                "admin", "192.168.1.1", "TestAgent/1.0", null
            );
            commandService.revokeAllConsentCommand("diana", "admin", "192.168.1.1", "TestAgent/1.0");
        });

        AggregateKey key = new AggregateKey(
            SubjectConsentAggregate.deriveId("diana"),
            SubjectConsentAggregate.AGGREGATE_TYPE,
            TENANT
        );
        SubjectConsentAggregate agg = eventService.getAggregate(key, SubjectConsentAggregate.class);

        assertTrue(
            agg.entries().stream().allMatch(e -> "REVOKE".equals(e.action())),
            "All entries should be revoked"
        );

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.CONSENT_ALL_REVOKED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // DSAR Aggregate — delete subject audit
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
    void deleteSubjectAuditCreatesDsarAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.deleteSubjectAuditCommand(
                "eve", "del-req-001", "dpo", "10.0.0.1", "AdminUI/1.0"
            )
        );

        AggregateKey key = new AggregateKey(
            DsarAggregate.deriveId("eve"),
            DsarAggregate.AGGREGATE_TYPE,
            TENANT
        );
        DsarAggregate agg = eventService.getAggregate(key, DsarAggregate.class);

        assertEquals("eve", agg.subjectId());
        assertEquals("del-req-001", agg.lastDeletionRequestId());

        awaitConsumedEvents(consumedEvents.size() + 1, 5000);
        assertFalse(consumedByType(ConsentEventTypes.SUBJECT_AUDIT_DELETED).isEmpty());
    }

    @Test
    @Order(41)
    void deleteSubjectAuditUpdatesExistingAggregate() throws Exception {
        TenantContext.runWhere(TENANT, () ->
            commandService.deleteSubjectAuditCommand(
                "eve", "del-req-002", "dpo", "10.0.0.1", "AdminUI/1.0"
            )
        );

        AggregateKey key = new AggregateKey(
            DsarAggregate.deriveId("eve"),
            DsarAggregate.AGGREGATE_TYPE,
            TENANT
        );
        DsarAggregate agg = eventService.getAggregate(key, DsarAggregate.class);

        assertEquals("del-req-002", agg.lastDeletionRequestId());
    }

    // -----------------------------------------------------------------------
    // Redis stream — aggregate-level verification
    // -----------------------------------------------------------------------

    @Test
    @Order(100)
    void allEventsContainRequiredStreamFields() throws Exception {
        awaitConsumedEvents(consumedEvents.size() + 1, 6000);
        Thread.sleep(3000);

        assertFalse(consumedEvents.isEmpty(), "Should have consumed at least one event from Redis");

        for (Map<String, String> event : consumedEvents) {
            assertNotNull(event.get(TenantEvent.FIELD_EVENT_ID), "Missing eventId");
            assertNotNull(event.get(TenantEvent.FIELD_TENANT_ID), "Missing tenantId");
            assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_ID), "Missing aggregateId");
            assertNotNull(event.get(TenantEvent.FIELD_AGGREGATE_TYPE), "Missing aggregateType");
            assertNotNull(event.get(TenantEvent.FIELD_EVENT_TYPE), "Missing eventType");
            assertNotNull(event.get(TenantEvent.FIELD_VERSION), "Missing version");
            assertNotNull(event.get(TenantEvent.FIELD_PAYLOAD), "Missing payload");
        }
    }

    // -----------------------------------------------------------------------
    // Negative / error-path tests
    // -----------------------------------------------------------------------

    @Test
    @Order(101)
    void grantConsentWithoutTenantContextThrows() {
        assertThrows(
            com.koostack.operio.core.exception.OperioConfigurationException.class,
            () -> commandService.grantConsentCommand(
                "no-tenant", 9999L, "marketing", "field", "email", "User", "PII",
                "admin", "127.0.0.1", "Test/1.0", null
            ),
            "Should throw when no tenant context is set"
        );
    }

    @Test
    @Order(102)
    void revokeConsentWithoutTenantContextThrows() {
        assertThrows(
            com.koostack.operio.core.exception.OperioConfigurationException.class,
            () -> commandService.revokeConsentCommand(
                "no-tenant", 9999L, "marketing", "field", "email", "User", "PII",
                "admin", "127.0.0.1", "Test/1.0"
            ),
            "Should throw when no tenant context is set"
        );
    }

    @Test
    @Order(103)
    void registerFieldWithoutTenantContextThrows() {
        assertThrows(
            com.koostack.operio.core.exception.OperioConfigurationException.class,
            () -> commandService.registerFieldCommand("email", "User", "desc", "entity desc"),
            "Should throw when no tenant context is set"
        );
    }

    @Test
    @Order(104)
    void grantConsentWithNullSubjectThrows() {
        assertThrows(Exception.class, () ->
            TenantContext.runWhere(TENANT, () ->
                commandService.grantConsentCommand(
                    null, 9999L, "marketing", "field", "email", "User", "PII",
                    "admin", "127.0.0.1", "Test/1.0", null
                )
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
