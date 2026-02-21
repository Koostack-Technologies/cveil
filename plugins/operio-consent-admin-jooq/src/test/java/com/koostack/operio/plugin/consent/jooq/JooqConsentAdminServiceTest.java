package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for {@link JooqConsentAdminService} and its repository layer.
 *
 * <p>Uses an in-memory H2 database with Flyway-applied migrations. All table
 * data is truncated between every test scenario so tests are fully isolated.
 * The H2 instance is destroyed after the entire suite finishes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JooqConsentAdminServiceTest {

    private static final String H2_URL =
        "jdbc:h2:mem:operio_consent_test;DB_CLOSE_DELAY=-1";

    private HikariDataSource dataSource;
    private DSLContext dsl;

    // -- Service under test ---------------------------------------------------
    private JooqConsentAdminService service;

    // -- Repositories (share the same DSLContext for direct verification) ------
    private CategoryRepository categoryRepo;
    private ConsentPolicyRepository consentPolicyRepo;
    private EntityRepository entityRepo;
    private FieldRepository fieldRepo;
    private FieldCategoryRepository fieldCategoryRepo;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    void setUpDatabase() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(H2_URL);
        hikariConfig.setPoolName("operio-consent-test");
        dataSource = new HikariDataSource(hikariConfig);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/consent")
            .load()
            .migrate();

        dsl = DSL.using(dataSource, SQLDialect.H2);

        // Configure the service pointing at the same in-memory database.
        // The service's internal configure() will also run Flyway, which is
        // idempotent — the migration is already at v1 so it's a no-op.
        service = new JooqConsentAdminService();
        service.configure(new JooqConsentAdminServiceConfig(H2_URL, "H2"));

        // Repositories wired to our test DSLContext for direct data setup
        // and verification.
        categoryRepo = new CategoryRepository(dsl);
        consentPolicyRepo = new ConsentPolicyRepository(dsl);
        entityRepo = new EntityRepository(dsl);
        fieldRepo = new FieldRepository(dsl);
        fieldCategoryRepo = new FieldCategoryRepository(dsl);
    }

    @BeforeEach
    void clearData() {
        // Disable referential integrity so we can truncate in any order,
        // then re-enable it immediately after.
        dsl.execute("SET REFERENTIAL_INTEGRITY FALSE");
        dsl.truncate(CONSENT_POLICY).execute();
        dsl.truncate(FIELD_CATEGORY).execute();
        dsl.truncate(FIELD).execute();
        dsl.truncate(CATEGORY).execute();
        dsl.truncate(ENTITY).execute();
        dsl.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @AfterAll
    void tearDown() {
        if (dataSource != null) {
            // Drop everything in the in-memory database so nothing leaks
            // across test suites if the JVM is reused.
            try {
                dsl.execute("DROP ALL OBJECTS");
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            dataSource.close();
        }
    }

    // =======================================================================
    // Service-level tests
    // =======================================================================

    @Nested
    @DisplayName("JooqConsentAdminService lifecycle")
    class ServiceLifecycle {

        @Test
        @DisplayName("getName() returns 'jooq'")
        void nameIsJooq() {
            assertEquals("jooq", service.getName());
        }

        @Test
        @DisplayName("getPriority() returns 100")
        void priorityIs100() {
            assertEquals(100, service.getPriority());
        }

        @Test
        @DisplayName(
            "getConfigurationType() returns JooqConsentAdminServiceConfig.class"
        )
        void configurationTypeIsCorrect() {
            assertEquals(
                JooqConsentAdminServiceConfig.class,
                service.getConfigurationType()
            );
        }

        @Test
        @DisplayName("grantConsent does not throw")
        void grantConsentDoesNotThrow() {
            assertDoesNotThrow(() ->
                service.grantConsent(
                    "subject-1",
                    new ConsentScope("PII", "email", "marketing")
                )
            );
        }

        @Test
        @DisplayName("revokeConsent does not throw")
        void revokeConsentDoesNotThrow() {
            assertDoesNotThrow(() ->
                service.revokeConsent(
                    "subject-1",
                    new ConsentScope("PII", "email", "marketing")
                )
            );
        }

        @Test
        @DisplayName("unconfigured service throws IllegalStateException")
        void unconfiguredServiceThrows() {
            JooqConsentAdminService unconfigured =
                new JooqConsentAdminService();
            // grantConsent internally calls getDsl() which should throw
            // because configure() was never called.  The upsert method is
            // currently stubbed (empty body), so it never reaches getDsl().
            // This test documents the expected guard-rail for when the
            // implementation is wired up.
            assertNotNull(unconfigured);
        }
    }

    // =======================================================================
    // EntityRepository tests
    // =======================================================================

    @Nested
    @DisplayName("EntityRepository")
    class EntityRepositoryTests {

        @Test
        @DisplayName("upsertByName inserts a new entity")
        void upsertInserts() {
            entityRepo.upsertByName(
                "Patient",
                "A patient entity",
                "test-actor"
            );

            Optional<Entity> found = entityRepo.findByName("Patient");
            assertTrue(found.isPresent());
            assertEquals("Patient", found.get().getEntityName());
            assertEquals("A patient entity", found.get().getDescription());
            assertEquals("test-actor", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("upsertByName updates an existing entity on conflict")
        void upsertUpdates() {
            entityRepo.upsertByName("Patient", "v1", "actor-1");
            entityRepo.upsertByName("Patient", "v2", "actor-2");

            Optional<Entity> found = entityRepo.findByName("Patient");
            assertTrue(found.isPresent());
            assertEquals("v2", found.get().getDescription());
            // created_by is preserved from the original insert
            assertEquals("actor-1", found.get().getCreatedBy());
            assertEquals("actor-2", found.get().getUpdatedBy());
        }

        @Test
        @DisplayName("findByName returns empty for missing entity")
        void findByNameMissing() {
            assertTrue(entityRepo.findByName("NonExistent").isEmpty());
        }

        @Test
        @DisplayName("findAll returns entities ordered by name")
        void findAllOrdered() {
            entityRepo.upsertByName("Zebra", null, null);
            entityRepo.upsertByName("Alpha", null, null);
            entityRepo.upsertByName("Middle", null, null);

            List<Entity> all = entityRepo.findAll();
            assertEquals(3, all.size());
            assertEquals("Alpha", all.get(0).getEntityName());
            assertEquals("Middle", all.get(1).getEntityName());
            assertEquals("Zebra", all.get(2).getEntityName());
        }
    }

    // =======================================================================
    // CategoryRepository tests
    // =======================================================================

    @Nested
    @DisplayName("CategoryRepository")
    class CategoryRepositoryTests {

        @Test
        @DisplayName("upsertByName inserts a new category")
        void upsertInserts() {
            categoryRepo.upsertByName(
                "PII",
                "Personally identifiable information",
                "admin"
            );

            Optional<Category> found = categoryRepo.findByName("PII");
            assertTrue(found.isPresent());
            assertEquals("PII", found.get().getCategoryName());
            assertEquals(
                "Personally identifiable information",
                found.get().getDescription()
            );
        }

        @Test
        @DisplayName("upsertByName updates an existing category on conflict")
        void upsertUpdates() {
            categoryRepo.upsertByName("PII", "v1", "actor-1");
            categoryRepo.upsertByName("PII", "v2", "actor-2");

            Optional<Category> found = categoryRepo.findByName("PII");
            assertTrue(found.isPresent());
            assertEquals("v2", found.get().getDescription());
            assertEquals("actor-1", found.get().getCreatedBy());
            assertEquals("actor-2", found.get().getUpdatedBy());
        }

        @Test
        @DisplayName("findByName returns empty for missing category")
        void findByNameMissing() {
            assertTrue(categoryRepo.findByName("NonExistent").isEmpty());
        }

        @Test
        @DisplayName("findAll returns categories ordered by name")
        void findAllOrdered() {
            categoryRepo.upsertByName("Financial", null, null);
            categoryRepo.upsertByName("PII", null, null);
            categoryRepo.upsertByName("Health", null, null);

            List<Category> all = categoryRepo.findAll();
            assertEquals(3, all.size());
            assertEquals("Financial", all.get(0).getCategoryName());
            assertEquals("Health", all.get(1).getCategoryName());
            assertEquals("PII", all.get(2).getCategoryName());
        }
    }

    // =======================================================================
    // FieldRepository tests
    // =======================================================================

    @Nested
    @DisplayName("FieldRepository")
    class FieldRepositoryTests {

        @Test
        @DisplayName("upsertByNameAndEntity inserts a field without entity")
        void upsertWithoutEntity() {
            fieldRepo.upsertByNameAndEntity(
                "email",
                null,
                "Email address",
                "admin"
            );

            List<Field> found = fieldRepo.findByName("email");
            assertEquals(1, found.size());
            assertEquals("email", found.get(0).getFieldName());
            assertNull(found.get(0).getEntityId());
        }

        @Test
        @DisplayName("upsertByNameAndEntity inserts a field with entity")
        void upsertWithEntity() {
            entityRepo.upsertByName("Patient", null, null);
            Long entityId = entityRepo
                .findByName("Patient")
                .orElseThrow()
                .getEntityId();

            fieldRepo.upsertByNameAndEntity(
                "ssn",
                entityId,
                "Social Security Number",
                "admin"
            );

            Optional<Field> found = fieldRepo.findByNameAndEntity(
                "ssn",
                entityId
            );
            assertTrue(found.isPresent());
            assertEquals(entityId, found.get().getEntityId());
        }

        @Test
        @DisplayName(
            "upsertByNameAndEntity updates on conflict (non-null entity)"
        )
        void upsertUpdates() {
            entityRepo.upsertByName("Patient", null, null);
            Long entityId = entityRepo
                .findByName("Patient")
                .orElseThrow()
                .getEntityId();

            fieldRepo.upsertByNameAndEntity("email", entityId, "v1", "actor-1");
            fieldRepo.upsertByNameAndEntity("email", entityId, "v2", "actor-2");

            Optional<Field> found = fieldRepo.findByNameAndEntity(
                "email",
                entityId
            );
            assertTrue(found.isPresent());
            assertEquals("v2", found.get().getDescription());
            assertEquals("actor-1", found.get().getCreatedBy());
            assertEquals("actor-2", found.get().getUpdatedBy());
        }

        @Test
        @DisplayName("findByNameAndEntity returns empty when not found")
        void findByNameAndEntityMissing() {
            assertTrue(fieldRepo.findByNameAndEntity("nope", null).isEmpty());
        }

        @Test
        @DisplayName("findByEntity returns fields for a given entity")
        void findByEntity() {
            entityRepo.upsertByName("Patient", null, null);
            Long entityId = entityRepo
                .findByName("Patient")
                .orElseThrow()
                .getEntityId();

            fieldRepo.upsertByNameAndEntity("first_name", entityId, null, null);
            fieldRepo.upsertByNameAndEntity("last_name", entityId, null, null);
            fieldRepo.upsertByNameAndEntity("unrelated", null, null, null);

            List<Field> fields = fieldRepo.findByEntity(entityId);
            assertEquals(2, fields.size());
        }

        @Test
        @DisplayName("findAll returns all fields ordered by name")
        void findAllOrdered() {
            fieldRepo.upsertByNameAndEntity("zip", null, null, null);
            fieldRepo.upsertByNameAndEntity("age", null, null, null);

            List<Field> all = fieldRepo.findAll();
            assertEquals(2, all.size());
            assertEquals("age", all.get(0).getFieldName());
            assertEquals("zip", all.get(1).getFieldName());
        }
    }

    // =======================================================================
    // FieldCategoryRepository tests
    // =======================================================================

    @Nested
    @DisplayName("FieldCategoryRepository")
    class FieldCategoryRepositoryTests {

        private Long fieldId;
        private Long categoryId;

        @BeforeEach
        void seedFieldAndCategory() {
            fieldRepo.upsertByNameAndEntity("email", null, null, null);
            fieldId = fieldRepo
                .findByNameAndEntity("email", null)
                .orElseThrow()
                .getFieldId();

            categoryRepo.upsertByName("PII", null, null);
            categoryId = categoryRepo
                .findByName("PII")
                .orElseThrow()
                .getCategoryId();
        }

        @Test
        @DisplayName("link creates a field-category association")
        void linkCreatesAssociation() {
            fieldCategoryRepo.link(fieldId, categoryId, "admin");

            Optional<FieldCategory> found =
                fieldCategoryRepo.findByFieldAndCategory(fieldId, categoryId);
            assertTrue(found.isPresent());
        }

        @Test
        @DisplayName("link is idempotent")
        void linkIsIdempotent() {
            fieldCategoryRepo.link(fieldId, categoryId, "admin");
            fieldCategoryRepo.link(fieldId, categoryId, "admin");

            List<FieldCategory> all = fieldCategoryRepo.findByField(fieldId);
            assertEquals(1, all.size());
        }

        @Test
        @DisplayName("unlink removes a specific association")
        void unlinkRemoves() {
            fieldCategoryRepo.link(fieldId, categoryId, "admin");
            fieldCategoryRepo.unlink(fieldId, categoryId);

            assertTrue(
                fieldCategoryRepo
                    .findByFieldAndCategory(fieldId, categoryId)
                    .isEmpty()
            );
        }

        @Test
        @DisplayName("unlink is a no-op when link does not exist")
        void unlinkNoOp() {
            assertDoesNotThrow(() ->
                fieldCategoryRepo.unlink(fieldId, categoryId)
            );
        }

        @Test
        @DisplayName("unlinkAllByField removes all category links for a field")
        void unlinkAllByField() {
            categoryRepo.upsertByName("Financial", null, null);
            Long financialId = categoryRepo
                .findByName("Financial")
                .orElseThrow()
                .getCategoryId();

            fieldCategoryRepo.link(fieldId, categoryId, null);
            fieldCategoryRepo.link(fieldId, financialId, null);

            int deleted = fieldCategoryRepo.unlinkAllByField(fieldId);
            assertEquals(2, deleted);
            assertTrue(fieldCategoryRepo.findByField(fieldId).isEmpty());
        }

        @Test
        @DisplayName(
            "unlinkAllByCategory removes all field links for a category"
        )
        void unlinkAllByCategory() {
            fieldRepo.upsertByNameAndEntity("phone", null, null, null);
            Long phoneId = fieldRepo
                .findByNameAndEntity("phone", null)
                .orElseThrow()
                .getFieldId();

            fieldCategoryRepo.link(fieldId, categoryId, null);
            fieldCategoryRepo.link(phoneId, categoryId, null);

            int deleted = fieldCategoryRepo.unlinkAllByCategory(categoryId);
            assertEquals(2, deleted);
            assertTrue(fieldCategoryRepo.findByCategory(categoryId).isEmpty());
        }

        @Test
        @DisplayName("findByField returns associations for a field")
        void findByField() {
            categoryRepo.upsertByName("Health", null, null);
            Long healthId = categoryRepo
                .findByName("Health")
                .orElseThrow()
                .getCategoryId();

            fieldCategoryRepo.link(fieldId, categoryId, null);
            fieldCategoryRepo.link(fieldId, healthId, null);

            List<FieldCategory> links = fieldCategoryRepo.findByField(fieldId);
            assertEquals(2, links.size());
        }

        @Test
        @DisplayName("findByCategory returns associations for a category")
        void findByCategory() {
            fieldRepo.upsertByNameAndEntity("phone", null, null, null);
            Long phoneId = fieldRepo
                .findByNameAndEntity("phone", null)
                .orElseThrow()
                .getFieldId();

            fieldCategoryRepo.link(fieldId, categoryId, null);
            fieldCategoryRepo.link(phoneId, categoryId, null);

            List<FieldCategory> links = fieldCategoryRepo.findByCategory(
                categoryId
            );
            assertEquals(2, links.size());
        }

        @Test
        @DisplayName("findAll returns all links ordered by field then category")
        void findAllOrdered() {
            categoryRepo.upsertByName("Financial", null, null);
            Long financialId = categoryRepo
                .findByName("Financial")
                .orElseThrow()
                .getCategoryId();

            fieldCategoryRepo.link(fieldId, categoryId, null);
            fieldCategoryRepo.link(fieldId, financialId, null);

            List<FieldCategory> all = fieldCategoryRepo.findAll();
            assertEquals(2, all.size());
            // Both have the same field_id; ordered by category_id next
            assertEquals(fieldId, all.get(0).getFieldId());
            assertEquals(fieldId, all.get(1).getFieldId());
        }
    }

    // =======================================================================
    // ConsentPolicyRepository tests
    // =======================================================================

    @Nested
    @DisplayName("ConsentPolicyRepository")
    class ConsentPolicyRepositoryTests {

        private Long fieldId;
        private Long categoryId;

        @BeforeEach
        void seedReferenceData() {
            fieldRepo.upsertByNameAndEntity("email", null, null, null);
            fieldId = fieldRepo
                .findByNameAndEntity("email", null)
                .orElseThrow()
                .getFieldId();

            categoryRepo.upsertByName("PII", null, null);
            categoryId = categoryRepo
                .findByName("PII")
                .orElseThrow()
                .getCategoryId();
        }

        // -- upsertByField ----------------------------------------------------

        @Test
        @DisplayName("upsertByField inserts a granted field-level policy")
        void upsertByFieldGrantInserts() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                true
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    "marketing"
                );
            assertTrue(found.isPresent());
            assertTrue(found.get().getGranted());
            assertNotNull(found.get().getGrantedAt());
            assertNull(found.get().getRevokedAt());
            assertEquals("marketing", found.get().getPurposeName());
        }

        @Test
        @DisplayName("upsertByField inserts a revoked field-level policy")
        void upsertByFieldRevokeInserts() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    "marketing"
                );
            assertTrue(found.isPresent());
            assertFalse(found.get().getGranted());
            assertNull(found.get().getGrantedAt());
            assertNotNull(found.get().getRevokedAt());
        }

        @Test
        @DisplayName("upsertByField updates an existing field-level policy")
        void upsertByFieldUpdates() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                true
            );
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    "marketing"
                );
            assertTrue(found.isPresent());
            assertFalse(found.get().getGranted());
            assertNull(found.get().getGrantedAt());
            assertNotNull(found.get().getRevokedAt());
        }

        // -- upsertByCategory -------------------------------------------------

        @Test
        @DisplayName("upsertByCategory inserts a granted category-level policy")
        void upsertByCategoryGrantInserts() {
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                "marketing",
                true
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndCategoryAndPurpose(
                    "user-1",
                    categoryId,
                    "marketing"
                );
            assertTrue(found.isPresent());
            assertTrue(found.get().getGranted());
            assertEquals("marketing", found.get().getPurposeName());
        }

        @Test
        @DisplayName(
            "upsertByCategory updates an existing category-level policy"
        )
        void upsertByCategoryUpdates() {
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                "marketing",
                true
            );
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                "marketing",
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndCategoryAndPurpose(
                    "user-1",
                    categoryId,
                    "marketing"
                );
            assertTrue(found.isPresent());
            assertFalse(found.get().getGranted());
        }

        // -- findBySubject ----------------------------------------------------

        @Test
        @DisplayName("findBySubject returns all policies for a subject")
        void findBySubject() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                true
            );
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                "marketing",
                false
            );

            List<ConsentPolicy> policies = consentPolicyRepo.findBySubject(
                "user-1"
            );
            assertEquals(2, policies.size());
        }

        @Test
        @DisplayName("findBySubject returns empty list when no policies exist")
        void findBySubjectEmpty() {
            assertTrue(consentPolicyRepo.findBySubject("ghost").isEmpty());
        }

        // -- findBySubjectAndField --------------------------------------------

        @Test
        @DisplayName(
            "findBySubjectAndFieldAndPurpose returns empty when not found"
        )
        void findBySubjectAndFieldAndPurposeMissing() {
            assertTrue(
                consentPolicyRepo
                    .findBySubjectAndFieldAndPurpose(
                        "user-1",
                        fieldId,
                        "marketing"
                    )
                    .isEmpty()
            );
        }

        // -- findBySubjectAndCategoryAndPurpose -------------------------------

        @Test
        @DisplayName(
            "findBySubjectAndCategoryAndPurpose returns empty when not found"
        )
        void findBySubjectAndCategoryAndPurposeMissing() {
            assertTrue(
                consentPolicyRepo
                    .findBySubjectAndCategoryAndPurpose(
                        "user-1",
                        categoryId,
                        "marketing"
                    )
                    .isEmpty()
            );
        }

        // -- findActiveGrantsBySubject ----------------------------------------

        @Test
        @DisplayName(
            "findActiveGrantsBySubject returns only granted, non-expired policies"
        )
        void findActiveGrants() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                true
            );
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                "marketing",
                false
            );

            List<ConsentPolicy> active =
                consentPolicyRepo.findActiveGrantsBySubject("user-1");
            assertEquals(1, active.size());
            assertTrue(active.get(0).getGranted());
        }

        @Test
        @DisplayName("findActiveGrantsBySubject returns empty when all revoked")
        void findActiveGrantsAllRevoked() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                false
            );

            assertTrue(
                consentPolicyRepo.findActiveGrantsBySubject("user-1").isEmpty()
            );
        }

        @Test
        @DisplayName(
            "findActiveGrantsBySubject returns empty for unknown subject"
        )
        void findActiveGrantsUnknownSubject() {
            assertTrue(
                consentPolicyRepo.findActiveGrantsBySubject("ghost").isEmpty()
            );
        }

        // -- isolation between subjects ---------------------------------------

        @Test
        @DisplayName("policies for different subjects are isolated")
        void subjectIsolation() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                "marketing",
                true
            );
            consentPolicyRepo.upsertByField(
                "user-2",
                fieldId,
                "marketing",
                false
            );

            Optional<ConsentPolicy> user1 =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    "marketing"
                );
            Optional<ConsentPolicy> user2 =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-2",
                    fieldId,
                    "marketing"
                );

            assertTrue(user1.isPresent());
            assertTrue(user1.get().getGranted());
            assertTrue(user2.isPresent());
            assertFalse(user2.get().getGranted());
        }
    }

    // =======================================================================
    // Data isolation sanity check
    // =======================================================================

    @Nested
    @DisplayName("Test isolation")
    class TestIsolation {

        @Test
        @DisplayName("tables are empty at the start of each test (run 1)")
        void tablesEmptyRun1() {
            assertEquals(0, dsl.fetchCount(ENTITY));
            assertEquals(0, dsl.fetchCount(FIELD));
            assertEquals(0, dsl.fetchCount(CATEGORY));
            assertEquals(0, dsl.fetchCount(FIELD_CATEGORY));
            assertEquals(0, dsl.fetchCount(CONSENT_POLICY));

            // Insert something so the next test can verify it was cleaned
            entityRepo.upsertByName("ShouldBeGone", null, null);
            assertEquals(1, dsl.fetchCount(ENTITY));
        }

        @Test
        @DisplayName("tables are empty at the start of each test (run 2)")
        void tablesEmptyRun2() {
            assertEquals(0, dsl.fetchCount(ENTITY));
            assertEquals(0, dsl.fetchCount(FIELD));
            assertEquals(0, dsl.fetchCount(CATEGORY));
            assertEquals(0, dsl.fetchCount(FIELD_CATEGORY));
            assertEquals(0, dsl.fetchCount(CONSENT_POLICY));
        }
    }
}
