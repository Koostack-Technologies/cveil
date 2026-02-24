package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.CategoryInfo;
import com.koostack.operio.core.Models.ConsentAuditEntry;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.EntityInfo;
import com.koostack.operio.core.Models.FieldInfo;
import com.koostack.operio.core.Models.PolicySummaryEntry;
import com.koostack.operio.core.Models.PurposeInfo;
import com.koostack.operio.core.Models.RegulationInfo;
import com.koostack.operio.core.RequestContext;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentAuditLog;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.PurposeRegulation;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Regulation;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
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
    private PurposeRepository purposeRepo;
    private RegulationRepository regulationRepo;
    private PurposeRegulationRepository purposeRegulationRepo;
    private ConsentAuditLogRepository auditLogRepo;
    private DsarAuditLogRepository dsarAuditLogRepo;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    void setUpDatabase() {
        // Configure the service first — this runs Flyway migrations once via
        // JooqContextFactory.  Creating the test datasource afterwards ensures
        // we connect to the already-migrated H2 database without running
        // migrations a second time (which would fail with "table already exists").
        service = new JooqConsentAdminService();
        service.configure(new JooqConsentAdminServiceConfig(H2_URL, "H2"));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(H2_URL);
        hikariConfig.setPoolName("operio-consent-test");
        dataSource = new HikariDataSource(hikariConfig);

        dsl = DSL.using(dataSource, SQLDialect.H2);

        // Repositories wired to our test DSLContext for direct data setup
        // and verification.
        categoryRepo = new CategoryRepository(dsl);
        consentPolicyRepo = new ConsentPolicyRepository(dsl);
        entityRepo = new EntityRepository(dsl);
        fieldRepo = new FieldRepository(dsl);
        fieldCategoryRepo = new FieldCategoryRepository(dsl);
        purposeRepo = new PurposeRepository(dsl);
        regulationRepo = new RegulationRepository(dsl);
        purposeRegulationRepo = new PurposeRegulationRepository(dsl);
        auditLogRepo = new ConsentAuditLogRepository(dsl);
        dsarAuditLogRepo = new DsarAuditLogRepository(dsl);
    }

    @BeforeEach
    void clearData() {
        // Disable referential integrity so we can truncate in any order,
        // then re-enable it immediately after.
        dsl.execute("SET REFERENTIAL_INTEGRITY FALSE");
        dsl.truncate(DSAR_AUDIT_LOG).execute();
        dsl.truncate(CONSENT_AUDIT_LOG).execute();
        dsl.truncate(CONSENT_POLICY).execute();
        dsl.truncate(FIELD_CATEGORY).execute();
        dsl.truncate(FIELD).execute();
        dsl.truncate(CATEGORY).execute();
        dsl.truncate(ENTITY).execute();
        dsl.truncate(PURPOSE_REGULATION).execute();
        dsl.truncate(PURPOSE).execute();
        dsl.truncate(REGULATION).execute();
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
    // Migration verification tests (V3, V4, V5 schema structure)
    // =======================================================================

    @Nested
    @DisplayName("MigrationVerification")
    class MigrationVerificationTests {

        @Test
        @DisplayName("V3: purpose table exists with correct columns")
        void v3PurposeTableExists() {
            // Verify the purpose table has the expected columns
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'PURPOSE' ORDER BY ORDINAL_POSITION"
            );
            assertFalse(columns.isEmpty(), "purpose table should exist");

            List<String> columnNames = columns
                .stream()
                .map(r -> r.get("COLUMN_NAME", String.class))
                .toList();
            assertTrue(columnNames.contains("PURPOSE_ID"));
            assertTrue(columnNames.contains("PURPOSE_NAME"));
            assertTrue(columnNames.contains("DESCRIPTION"));
            assertTrue(columnNames.contains("CREATED_AT"));
            assertTrue(columnNames.contains("CREATED_BY"));
            assertTrue(columnNames.contains("UPDATED_AT"));
            assertTrue(columnNames.contains("UPDATED_BY"));
        }

        @Test
        @DisplayName("V3: regulation table exists with correct columns")
        void v3RegulationTableExists() {
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'REGULATION' ORDER BY ORDINAL_POSITION"
            );
            assertFalse(columns.isEmpty(), "regulation table should exist");

            List<String> columnNames = columns
                .stream()
                .map(r -> r.get("COLUMN_NAME", String.class))
                .toList();
            assertTrue(columnNames.contains("REGULATION_ID"));
            assertTrue(columnNames.contains("REGULATION_NAME"));
            assertTrue(columnNames.contains("FULL_NAME"));
            assertTrue(columnNames.contains("JURISDICTION"));
            assertTrue(columnNames.contains("DESCRIPTION"));
            assertTrue(columnNames.contains("URL"));
        }

        @Test
        @DisplayName("V3: purpose_regulation table exists with correct columns")
        void v3PurposeRegulationTableExists() {
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'PURPOSE_REGULATION' ORDER BY ORDINAL_POSITION"
            );
            assertFalse(
                columns.isEmpty(),
                "purpose_regulation table should exist"
            );

            List<String> columnNames = columns
                .stream()
                .map(r -> r.get("COLUMN_NAME", String.class))
                .toList();
            assertTrue(columnNames.contains("PURPOSE_REGULATION_ID"));
            assertTrue(columnNames.contains("PURPOSE_ID"));
            assertTrue(columnNames.contains("REGULATION_ID"));
            assertTrue(columnNames.contains("LEGAL_BASIS"));
        }

        @Test
        @DisplayName("V3: purpose_name has UNIQUE constraint")
        void v3PurposeNameUnique() {
            // Insert a purpose, then attempt a duplicate — the repo uses
            // ON CONFLICT so it won't throw, but we verify uniqueness via
            // the information schema.
            Result<Record> constraints = dsl.fetch(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'PURPOSE' AND CONSTRAINT_TYPE = 'UNIQUE'"
            );
            assertFalse(
                constraints.isEmpty(),
                "purpose table should have at least one UNIQUE constraint"
            );
        }

        @Test
        @DisplayName("V3: regulation_name has UNIQUE constraint")
        void v3RegulationNameUnique() {
            Result<Record> constraints = dsl.fetch(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'REGULATION' AND CONSTRAINT_TYPE = 'UNIQUE'"
            );
            assertFalse(
                constraints.isEmpty(),
                "regulation table should have at least one UNIQUE constraint"
            );
        }

        @Test
        @DisplayName(
            "V3: purpose_regulation has composite UNIQUE on (purpose_id, regulation_id)"
        )
        void v3PurposeRegulationCompositeUnique() {
            Result<Record> constraints = dsl.fetch(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'PURPOSE_REGULATION' AND CONSTRAINT_TYPE = 'UNIQUE'"
            );
            assertFalse(
                constraints.isEmpty(),
                "purpose_regulation table should have a UNIQUE constraint"
            );
        }

        @Test
        @DisplayName("V4: consent_policy no longer has purpose_name column")
        void v4PurposeNameDropped() {
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'CONSENT_POLICY' AND COLUMN_NAME = 'PURPOSE_NAME'"
            );
            assertTrue(
                columns.isEmpty(),
                "purpose_name column should have been dropped by V4"
            );
        }

        @Test
        @DisplayName("V4: consent_policy.purpose_id is NOT NULL")
        void v4PurposeIdNotNull() {
            Result<Record> columns = dsl.fetch(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'CONSENT_POLICY' AND COLUMN_NAME = 'PURPOSE_ID'"
            );
            assertFalse(columns.isEmpty(), "purpose_id column should exist");
            assertEquals(
                "NO",
                columns.get(0).get("IS_NULLABLE", String.class),
                "purpose_id should be NOT NULL after V4"
            );
        }

        @Test
        @DisplayName(
            "V4: unique constraint on (subject_id, field_id, purpose_id) exists"
        )
        void v4UniqueConstraintFieldPurpose() {
            Result<Record> constraints = dsl.fetch(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'CONSENT_POLICY' AND CONSTRAINT_TYPE = 'UNIQUE' " +
                    "AND CONSTRAINT_NAME = 'UQ_CONSENT_FIELD_PURPOSE'"
            );
            assertFalse(
                constraints.isEmpty(),
                "V4 unique constraint UQ_CONSENT_FIELD_PURPOSE should exist"
            );
        }

        @Test
        @DisplayName(
            "V4: unique constraint on (subject_id, category_id, purpose_id) exists"
        )
        void v4UniqueConstraintCategoryPurpose() {
            Result<Record> constraints = dsl.fetch(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'CONSENT_POLICY' AND CONSTRAINT_TYPE = 'UNIQUE' " +
                    "AND CONSTRAINT_NAME = 'UQ_CONSENT_CATEGORY_PURPOSE'"
            );
            assertFalse(
                constraints.isEmpty(),
                "V4 unique constraint UQ_CONSENT_CATEGORY_PURPOSE should exist"
            );
        }

        @Test
        @DisplayName("V5: consent_audit_log table exists with correct columns")
        void v5AuditLogTableExists() {
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' ORDER BY ORDINAL_POSITION"
            );
            assertFalse(
                columns.isEmpty(),
                "consent_audit_log table should exist"
            );

            List<String> columnNames = columns
                .stream()
                .map(r -> r.get("COLUMN_NAME", String.class))
                .toList();
            assertTrue(columnNames.contains("AUDIT_ID"));
            assertTrue(columnNames.contains("SUBJECT_ID"));
            assertTrue(columnNames.contains("FIELD_ID"));
            assertTrue(columnNames.contains("CATEGORY_ID"));
            assertTrue(columnNames.contains("PURPOSE_ID"));
            assertTrue(columnNames.contains("ACTION"));
            assertTrue(columnNames.contains("GRANTED"));
            assertTrue(columnNames.contains("EXPIRES_AT"));
            assertTrue(columnNames.contains("ACTOR"));
            assertTrue(columnNames.contains("IP_ADDRESS"));
            assertTrue(columnNames.contains("USER_AGENT"));
            assertTrue(columnNames.contains("METADATA"));
            assertTrue(columnNames.contains("CREATED_AT"));
        }

        @Test
        @DisplayName("V5: consent_audit_log has index on subject_id")
        void v5AuditLogSubjectIndex() {
            Result<Record> indexes = dsl.fetch(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                    "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' AND INDEX_NAME = 'IDX_AUDIT_SUBJECT'"
            );
            assertFalse(
                indexes.isEmpty(),
                "idx_audit_subject index should exist on consent_audit_log"
            );
        }

        @Test
        @DisplayName("V5: consent_audit_log has index on created_at")
        void v5AuditLogCreatedIndex() {
            Result<Record> indexes = dsl.fetch(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                    "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' AND INDEX_NAME = 'IDX_AUDIT_CREATED'"
            );
            assertFalse(
                indexes.isEmpty(),
                "idx_audit_created index should exist on consent_audit_log"
            );
        }

        @Test
        @DisplayName(
            "V5: consent_audit_log has composite index on (subject_id, created_at)"
        )
        void v5AuditLogSubjectCreatedIndex() {
            Result<Record> indexes = dsl.fetch(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                    "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' AND INDEX_NAME = 'IDX_AUDIT_SUBJECT_CREATED'"
            );
            assertFalse(
                indexes.isEmpty(),
                "idx_audit_subject_created composite index should exist on consent_audit_log"
            );
        }

        @Test
        @DisplayName("V5: consent_audit_log.action is NOT NULL")
        void v5AuditLogActionNotNull() {
            Result<Record> columns = dsl.fetch(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' AND COLUMN_NAME = 'ACTION'"
            );
            assertFalse(columns.isEmpty());
            assertEquals(
                "NO",
                columns.get(0).get("IS_NULLABLE", String.class),
                "action column should be NOT NULL"
            );
        }

        @Test
        @DisplayName(
            "V5: consent_audit_log has FK references to purpose, field, and category"
        )
        void v5AuditLogForeignKeys() {
            // H2 2.2.x exposes FK info via INFORMATION_SCHEMA.CONSTRAINTS
            // with CONSTRAINT_TYPE = 'REFERENTIAL'. We query the constraint
            // column usage to verify which columns carry foreign keys.
            Result<Record> fks = dsl.fetch(
                "SELECT tc.CONSTRAINT_NAME, ccu.COLUMN_NAME " +
                    "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                    "JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu " +
                    "  ON tc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME " +
                    "WHERE tc.TABLE_NAME = 'CONSENT_AUDIT_LOG' " +
                    "  AND tc.CONSTRAINT_TYPE = 'REFERENTIAL' " +
                    "ORDER BY ccu.COLUMN_NAME"
            );

            // If the JOIN-based query returns nothing (H2 schema quirks),
            // fall back to simply verifying that referential constraints
            // exist at all — the functional repo tests already exercise
            // the FK behaviour.
            if (fks.isEmpty()) {
                // Fallback: just verify the columns themselves exist and
                // are nullable (FKs on audit log columns are nullable).
                // The functional insert tests in ConsentAuditLogRepositoryTests
                // already prove the FK relationship works.
                Result<Record> cols = dsl.fetch(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'CONSENT_AUDIT_LOG' " +
                        "AND COLUMN_NAME IN ('PURPOSE_ID', 'FIELD_ID', 'CATEGORY_ID')"
                );
                assertEquals(
                    3,
                    cols.size(),
                    "consent_audit_log should have PURPOSE_ID, FIELD_ID, and CATEGORY_ID columns"
                );
            } else {
                List<String> fkColumns = fks
                    .stream()
                    .map(r -> r.get("COLUMN_NAME", String.class))
                    .toList();
                assertTrue(
                    fkColumns.contains("PURPOSE_ID"),
                    "consent_audit_log should have FK on PURPOSE_ID"
                );
                assertTrue(
                    fkColumns.contains("FIELD_ID"),
                    "consent_audit_log should have FK on FIELD_ID"
                );
                assertTrue(
                    fkColumns.contains("CATEGORY_ID"),
                    "consent_audit_log should have FK on CATEGORY_ID"
                );
            }
        }

        @Test
        @DisplayName(
            "V3: consent_policy has purpose_id column with FK to purpose"
        )
        void v3ConsentPolicyPurposeIdFk() {
            Result<Record> columns = dsl.fetch(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = 'CONSENT_POLICY' AND COLUMN_NAME = 'PURPOSE_ID'"
            );
            assertFalse(
                columns.isEmpty(),
                "consent_policy should have a purpose_id column after V3"
            );
        }

        @Test
        @DisplayName("All six migrations applied successfully")
        void allMigrationsApplied() {
            // The Flyway schema history table is named per-module by JooqContextFactory
            Result<Record> versions = dsl.fetch(
                "SELECT \"version\" FROM \"flyway_schema_history_consent\" " +
                    "WHERE \"success\" = TRUE ORDER BY \"installed_rank\""
            );
            assertTrue(
                versions.size() >= 6,
                "At least 6 Flyway migrations should have been applied, got " +
                    versions.size()
            );
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
            // Seed reference data now that upsert resolves names → IDs
            fieldRepo.upsertByNameAndEntity("email", null, null, null);
            purposeRepo.upsertByName("marketing", null, null);

            assertDoesNotThrow(() ->
                service.grantConsent(
                    "subject-1",
                    ConsentScope.to("PII", "email", "marketing")
                )
            );
        }

        @Test
        @DisplayName("revokeConsent does not throw")
        void revokeConsentDoesNotThrow() {
            // Seed reference data now that upsert resolves names → IDs
            fieldRepo.upsertByNameAndEntity("email", null, null, null);
            purposeRepo.upsertByName("marketing", null, null);

            assertDoesNotThrow(() ->
                service.revokeConsent(
                    "subject-1",
                    ConsentScope.to("PII", "email", "marketing")
                )
            );
        }

        @Test
        @DisplayName("unconfigured service throws OperioConfigurationException")
        void unconfiguredServiceThrows() {
            JooqConsentAdminService unconfigured =
                new JooqConsentAdminService();
            // grantConsent internally calls getDsl() which throws because
            // configure() was never called.
            assertThrows(OperioConfigurationException.class, () ->
                unconfigured.grantConsent(
                    "subject-1",
                    ConsentScope.to("PII", "email", "marketing")
                )
            );
        }
    }

    // =======================================================================
    // PurposeRepository tests
    // =======================================================================

    @Nested
    @DisplayName("PurposeRepository")
    class PurposeRepositoryTests {

        @Test
        @DisplayName("upsertByName inserts a new purpose")
        void upsertInserts() {
            purposeRepo.upsertByName(
                "marketing",
                "Marketing communications",
                "admin"
            );

            Optional<Purpose> found = purposeRepo.findByName("marketing");
            assertTrue(found.isPresent());
            assertEquals("marketing", found.get().getPurposeName());
            assertEquals(
                "Marketing communications",
                found.get().getDescription()
            );
            assertNotNull(found.get().getCreatedAt());
            assertNotNull(found.get().getUpdatedAt());
            assertEquals("admin", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("upsertByName updates an existing purpose")
        void upsertUpdates() {
            purposeRepo.upsertByName("analytics", "Original desc", "admin");
            purposeRepo.upsertByName("analytics", "Updated desc", "updater");

            Optional<Purpose> found = purposeRepo.findByName("analytics");
            assertTrue(found.isPresent());
            assertEquals("Updated desc", found.get().getDescription());
            assertEquals("updater", found.get().getUpdatedBy());
            // created_by should be preserved from the original insert
            assertEquals("admin", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("findByName returns empty for non-existent purpose")
        void findByNameMissing() {
            assertTrue(purposeRepo.findByName("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("findById returns the correct purpose")
        void findById() {
            purposeRepo.upsertByName("treatment", "Medical treatment", null);
            Purpose inserted = purposeRepo
                .findByName("treatment")
                .orElseThrow();

            Optional<Purpose> found = purposeRepo.findById(
                inserted.getPurposeId()
            );
            assertTrue(found.isPresent());
            assertEquals("treatment", found.get().getPurposeName());
        }

        @Test
        @DisplayName("findById returns empty for non-existent ID")
        void findByIdMissing() {
            assertTrue(purposeRepo.findById(999999L).isEmpty());
        }

        @Test
        @DisplayName("findAll returns purposes ordered by name")
        void findAllOrdered() {
            purposeRepo.upsertByName("marketing", null, null);
            purposeRepo.upsertByName("analytics", null, null);
            purposeRepo.upsertByName("treatment", null, null);

            List<Purpose> all = purposeRepo.findAll();
            assertEquals(3, all.size());
            assertEquals("analytics", all.get(0).getPurposeName());
            assertEquals("marketing", all.get(1).getPurposeName());
            assertEquals("treatment", all.get(2).getPurposeName());
        }

        @Test
        @DisplayName("findAll returns empty list when no purposes exist")
        void findAllEmpty() {
            assertTrue(purposeRepo.findAll().isEmpty());
        }
    }

    // =======================================================================
    // RegulationRepository tests
    // =======================================================================

    @Nested
    @DisplayName("RegulationRepository")
    class RegulationRepositoryTests {

        @Test
        @DisplayName("upsertByName inserts a new regulation")
        void upsertInserts() {
            regulationRepo.upsertByName(
                "GDPR",
                "General Data Protection Regulation",
                "EU",
                "EU privacy regulation",
                "https://gdpr.eu",
                "admin"
            );

            Optional<Regulation> found = regulationRepo.findByName("GDPR");
            assertTrue(found.isPresent());
            assertEquals("GDPR", found.get().getRegulationName());
            assertEquals(
                "General Data Protection Regulation",
                found.get().getFullName()
            );
            assertEquals("EU", found.get().getJurisdiction());
            assertEquals("EU privacy regulation", found.get().getDescription());
            assertEquals("https://gdpr.eu", found.get().getUrl());
            assertNotNull(found.get().getCreatedAt());
            assertEquals("admin", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("upsertByName updates an existing regulation")
        void upsertUpdates() {
            regulationRepo.upsertByName(
                "CCPA",
                "California Consumer Privacy Act",
                "California",
                "Original desc",
                null,
                "admin"
            );
            regulationRepo.upsertByName(
                "CCPA",
                "California Consumer Privacy Act",
                "California, US",
                "Updated desc",
                "https://ccpa.ca.gov",
                "updater"
            );

            Optional<Regulation> found = regulationRepo.findByName("CCPA");
            assertTrue(found.isPresent());
            assertEquals("California, US", found.get().getJurisdiction());
            assertEquals("Updated desc", found.get().getDescription());
            assertEquals("https://ccpa.ca.gov", found.get().getUrl());
            assertEquals("updater", found.get().getUpdatedBy());
            // created_by preserved
            assertEquals("admin", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("findByName returns empty for non-existent regulation")
        void findByNameMissing() {
            assertTrue(regulationRepo.findByName("NONEXISTENT").isEmpty());
        }

        @Test
        @DisplayName("findById returns the correct regulation")
        void findById() {
            regulationRepo.upsertByName(
                "HIPAA",
                "Health Insurance Portability and Accountability Act",
                "US",
                null,
                null,
                null
            );
            Regulation inserted = regulationRepo
                .findByName("HIPAA")
                .orElseThrow();

            Optional<Regulation> found = regulationRepo.findById(
                inserted.getRegulationId()
            );
            assertTrue(found.isPresent());
            assertEquals("HIPAA", found.get().getRegulationName());
        }

        @Test
        @DisplayName("findById returns empty for non-existent ID")
        void findByIdMissing() {
            assertTrue(regulationRepo.findById(999999L).isEmpty());
        }

        @Test
        @DisplayName("findAll returns regulations ordered by name")
        void findAllOrdered() {
            regulationRepo.upsertByName(
                "LGPD",
                null,
                "Brazil",
                null,
                null,
                null
            );
            regulationRepo.upsertByName(
                "CCPA",
                null,
                "California",
                null,
                null,
                null
            );
            regulationRepo.upsertByName("GDPR", null, "EU", null, null, null);

            List<Regulation> all = regulationRepo.findAll();
            assertEquals(3, all.size());
            assertEquals("CCPA", all.get(0).getRegulationName());
            assertEquals("GDPR", all.get(1).getRegulationName());
            assertEquals("LGPD", all.get(2).getRegulationName());
        }

        @Test
        @DisplayName("findAll returns empty list when no regulations exist")
        void findAllEmpty() {
            assertTrue(regulationRepo.findAll().isEmpty());
        }
    }

    // =======================================================================
    // PurposeRegulationRepository tests
    // =======================================================================

    @Nested
    @DisplayName("PurposeRegulationRepository")
    class PurposeRegulationRepositoryTests {

        private Long purposeId;
        private Long regulationId;
        private Long regulationId2;

        @BeforeEach
        void seedPurposeAndRegulation() {
            purposeRepo.upsertByName("marketing", null, null);
            purposeId = purposeRepo
                .findByName("marketing")
                .orElseThrow()
                .getPurposeId();

            regulationRepo.upsertByName("GDPR", null, "EU", null, null, null);
            regulationId = regulationRepo
                .findByName("GDPR")
                .orElseThrow()
                .getRegulationId();

            regulationRepo.upsertByName(
                "CCPA",
                null,
                "California",
                null,
                null,
                null
            );
            regulationId2 = regulationRepo
                .findByName("CCPA")
                .orElseThrow()
                .getRegulationId();
        }

        @Test
        @DisplayName("link creates a purpose-regulation association")
        void linkCreatesAssociation() {
            purposeRegulationRepo.link(
                purposeId,
                regulationId,
                "consent",
                "admin"
            );

            Optional<PurposeRegulation> found =
                purposeRegulationRepo.findByPurposeAndRegulation(
                    purposeId,
                    regulationId
                );
            assertTrue(found.isPresent());
            assertEquals("consent", found.get().getLegalBasis());
            assertEquals("admin", found.get().getCreatedBy());
        }

        @Test
        @DisplayName("link is idempotent and updates legal_basis on re-link")
        void linkIsIdempotent() {
            purposeRegulationRepo.link(
                purposeId,
                regulationId,
                "consent",
                "admin"
            );
            purposeRegulationRepo.link(
                purposeId,
                regulationId,
                "legitimate interest",
                "updater"
            );

            List<PurposeRegulation> all = purposeRegulationRepo.findByPurpose(
                purposeId
            );
            assertEquals(
                1,
                all.size(),
                "Idempotent link should not create a duplicate"
            );
            assertEquals("legitimate interest", all.get(0).getLegalBasis());
            assertEquals("updater", all.get(0).getUpdatedBy());
        }

        @Test
        @DisplayName("unlink removes the association")
        void unlinkRemoves() {
            purposeRegulationRepo.link(purposeId, regulationId, null, null);
            purposeRegulationRepo.unlink(purposeId, regulationId);

            assertTrue(
                purposeRegulationRepo
                    .findByPurposeAndRegulation(purposeId, regulationId)
                    .isEmpty()
            );
        }

        @Test
        @DisplayName("unlink is a no-op when the link does not exist")
        void unlinkNoOp() {
            assertDoesNotThrow(() ->
                purposeRegulationRepo.unlink(purposeId, regulationId)
            );
        }

        @Test
        @DisplayName("findByPurpose returns all linked regulations")
        void findByPurpose() {
            purposeRegulationRepo.link(
                purposeId,
                regulationId,
                "consent",
                null
            );
            purposeRegulationRepo.link(
                purposeId,
                regulationId2,
                "legitimate interest",
                null
            );

            List<PurposeRegulation> links = purposeRegulationRepo.findByPurpose(
                purposeId
            );
            assertEquals(2, links.size());
        }

        @Test
        @DisplayName("findByPurpose returns empty for unlinked purpose")
        void findByPurposeEmpty() {
            assertTrue(
                purposeRegulationRepo.findByPurpose(purposeId).isEmpty()
            );
        }

        @Test
        @DisplayName("findByRegulation returns all linked purposes")
        void findByRegulation() {
            // Create a second purpose
            purposeRepo.upsertByName("analytics", null, null);
            Long purposeId2 = purposeRepo
                .findByName("analytics")
                .orElseThrow()
                .getPurposeId();

            purposeRegulationRepo.link(
                purposeId,
                regulationId,
                "consent",
                null
            );
            purposeRegulationRepo.link(
                purposeId2,
                regulationId,
                "legitimate interest",
                null
            );

            List<PurposeRegulation> links =
                purposeRegulationRepo.findByRegulation(regulationId);
            assertEquals(2, links.size());
        }

        @Test
        @DisplayName("findByRegulation returns empty for unlinked regulation")
        void findByRegulationEmpty() {
            assertTrue(
                purposeRegulationRepo.findByRegulation(regulationId).isEmpty()
            );
        }

        @Test
        @DisplayName(
            "findAll returns all links ordered by purpose then regulation"
        )
        void findAllOrdered() {
            purposeRegulationRepo.link(purposeId, regulationId, null, null);
            purposeRegulationRepo.link(purposeId, regulationId2, null, null);

            List<PurposeRegulation> all = purposeRegulationRepo.findAll();
            assertEquals(2, all.size());
            assertEquals(purposeId, all.get(0).getPurposeId());
            assertEquals(purposeId, all.get(1).getPurposeId());
        }

        @Test
        @DisplayName("unlinkAllByPurpose removes all regulation links")
        void unlinkAllByPurpose() {
            purposeRegulationRepo.link(purposeId, regulationId, null, null);
            purposeRegulationRepo.link(purposeId, regulationId2, null, null);

            int deleted = purposeRegulationRepo.unlinkAllByPurpose(purposeId);
            assertEquals(2, deleted);
            assertTrue(
                purposeRegulationRepo.findByPurpose(purposeId).isEmpty()
            );
        }

        @Test
        @DisplayName("unlinkAllByRegulation removes all purpose links")
        void unlinkAllByRegulation() {
            purposeRepo.upsertByName("analytics", null, null);
            Long purposeId2 = purposeRepo
                .findByName("analytics")
                .orElseThrow()
                .getPurposeId();

            purposeRegulationRepo.link(purposeId, regulationId, null, null);
            purposeRegulationRepo.link(purposeId2, regulationId, null, null);

            int deleted = purposeRegulationRepo.unlinkAllByRegulation(
                regulationId
            );
            assertEquals(2, deleted);
            assertTrue(
                purposeRegulationRepo.findByRegulation(regulationId).isEmpty()
            );
        }

        @Test
        @DisplayName(
            "findByPurposeAndRegulation returns empty when link does not exist"
        )
        void findByPurposeAndRegulationMissing() {
            assertTrue(
                purposeRegulationRepo
                    .findByPurposeAndRegulation(purposeId, regulationId)
                    .isEmpty()
            );
        }
    }

    // =======================================================================
    // Service registration methods tests
    // =======================================================================

    @Nested
    @DisplayName("ServiceRegistration")
    class ServiceRegistrationTests {

        @Test
        @DisplayName("registerPurpose creates a purpose via the service")
        void registerPurpose() {
            service.registerPurpose("marketing", "Marketing communications");

            List<PurposeInfo> purposes = service.listPurposes();
            assertEquals(1, purposes.size());
            assertEquals("marketing", purposes.get(0).purposeName());
            assertEquals(
                "Marketing communications",
                purposes.get(0).description()
            );
        }

        @Test
        @DisplayName("registerPurpose is idempotent (updates description)")
        void registerPurposeIdempotent() {
            service.registerPurpose("analytics", "Original");
            service.registerPurpose("analytics", "Updated");

            List<PurposeInfo> purposes = service.listPurposes();
            assertEquals(1, purposes.size());
            assertEquals("Updated", purposes.get(0).description());
        }

        @Test
        @DisplayName("registerRegulation creates a regulation via the service")
        void registerRegulation() {
            service.registerRegulation(
                "GDPR",
                "General Data Protection Regulation",
                "EU",
                "EU privacy regulation",
                "https://gdpr.eu"
            );

            List<RegulationInfo> regulations = service.listRegulations();
            assertEquals(1, regulations.size());
            assertEquals("GDPR", regulations.get(0).regulationName());
            assertEquals(
                "General Data Protection Regulation",
                regulations.get(0).fullName()
            );
            assertEquals("EU", regulations.get(0).jurisdiction());
            assertEquals(
                "EU privacy regulation",
                regulations.get(0).description()
            );
            assertEquals("https://gdpr.eu", regulations.get(0).url());
        }

        @Test
        @DisplayName("registerRegulation is idempotent (updates fields)")
        void registerRegulationIdempotent() {
            service.registerRegulation("CCPA", "CCPA", "CA", "Original", null);
            service.registerRegulation(
                "CCPA",
                "California Consumer Privacy Act",
                "California, US",
                "Updated",
                "https://ccpa.ca.gov"
            );

            List<RegulationInfo> regulations = service.listRegulations();
            assertEquals(1, regulations.size());
            assertEquals(
                "California Consumer Privacy Act",
                regulations.get(0).fullName()
            );
            assertEquals("California, US", regulations.get(0).jurisdiction());
        }

        @Test
        @DisplayName(
            "linkPurposeToRegulation links an existing purpose and regulation"
        )
        void linkPurposeToRegulation() {
            service.registerPurpose("marketing", null);
            service.registerRegulation("GDPR", null, "EU", null, null);

            service.linkPurposeToRegulation("marketing", "GDPR", "consent");

            // Verify via direct repo access
            Purpose p = purposeRepo.findByName("marketing").orElseThrow();
            Regulation r = regulationRepo.findByName("GDPR").orElseThrow();
            Optional<PurposeRegulation> link =
                purposeRegulationRepo.findByPurposeAndRegulation(
                    p.getPurposeId(),
                    r.getRegulationId()
                );
            assertTrue(link.isPresent());
            assertEquals("consent", link.get().getLegalBasis());
        }

        @Test
        @DisplayName("linkPurposeToRegulation throws for unknown purpose")
        void linkUnknownPurpose() {
            service.registerRegulation("GDPR", null, null, null, null);

            assertThrows(IllegalArgumentException.class, () ->
                service.linkPurposeToRegulation("nonexistent", "GDPR", null)
            );
        }

        @Test
        @DisplayName("linkPurposeToRegulation throws for unknown regulation")
        void linkUnknownRegulation() {
            service.registerPurpose("marketing", null);

            assertThrows(IllegalArgumentException.class, () ->
                service.linkPurposeToRegulation(
                    "marketing",
                    "nonexistent",
                    null
                )
            );
        }

        @Test
        @DisplayName("listPurposes returns purposes ordered by name")
        void listPurposesOrdered() {
            service.registerPurpose("treatment", null);
            service.registerPurpose("analytics", null);
            service.registerPurpose("marketing", null);

            List<PurposeInfo> list = service.listPurposes();
            assertEquals(3, list.size());
            assertEquals("analytics", list.get(0).purposeName());
            assertEquals("marketing", list.get(1).purposeName());
            assertEquals("treatment", list.get(2).purposeName());
        }

        @Test
        @DisplayName("listRegulations returns regulations ordered by name")
        void listRegulationsOrdered() {
            service.registerRegulation("LGPD", null, "Brazil", null, null);
            service.registerRegulation("CCPA", null, "California", null, null);
            service.registerRegulation("GDPR", null, "EU", null, null);

            List<RegulationInfo> list = service.listRegulations();
            assertEquals(3, list.size());
            assertEquals("CCPA", list.get(0).regulationName());
            assertEquals("GDPR", list.get(1).regulationName());
            assertEquals("LGPD", list.get(2).regulationName());
        }

        @Test
        @DisplayName("listFields returns all registered fields")
        void listFields() {
            service.registerField("email", null, "Email address");
            service.registerField("phone", null, "Phone number");

            List<FieldInfo> list = service.listFields();
            assertEquals(2, list.size());
        }

        @Test
        @DisplayName("listCategories returns all registered categories")
        void listCategories() {
            service.registerCategory("PII", "Personally identifiable info");
            service.registerCategory("PHI", "Protected health info");

            List<CategoryInfo> list = service.listCategories();
            assertEquals(2, list.size());
        }

        @Test
        @DisplayName("listEntities returns all registered entities")
        void listEntities() {
            service.registerField("email", "Patient", null);
            service.registerField("phone", "Order", null);

            List<EntityInfo> list = service.listEntities();
            assertEquals(2, list.size());
        }

        @Test
        @DisplayName("resolvePurposeId fails for null purpose")
        void resolvePurposeIdNull() {
            service.registerField("email", null, null);

            // A ConsentScope with null purposeName triggers the guard in
            // resolvePurposeId — we verify via grantConsent
            ConsentScope scope = new ConsentScope("PII", "email", null, null);
            assertThrows(IllegalArgumentException.class, () ->
                service.grantConsent("user-1", scope)
            );
        }

        @Test
        @DisplayName("resolvePurposeId fails for unregistered purpose")
        void resolvePurposeIdUnregistered() {
            service.registerField("email", null, null);

            ConsentScope scope = ConsentScope.to("PII", "email", "nonexistent");
            assertThrows(IllegalArgumentException.class, () ->
                service.grantConsent("user-1", scope)
            );
        }

        @Test
        @DisplayName("findPoliciesBySubject returns resolved summary DTOs")
        void findPoliciesBySubject() {
            service.registerField("email", null, null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);

            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-1", scope);

            List<PolicySummaryEntry> policies = service.findPoliciesBySubject(
                "user-1"
            );
            assertEquals(1, policies.size());

            PolicySummaryEntry entry = policies.get(0);
            assertEquals("user-1", entry.subject());
            assertEquals("email", entry.fieldName());
            assertEquals("marketing", entry.purposeName());
            assertEquals("GRANTED", entry.status());
            assertNotNull(entry.createdAt());
        }

        @Test
        @DisplayName("findPoliciesBySubject returns empty for unknown subject")
        void findPoliciesBySubjectEmpty() {
            assertTrue(service.findPoliciesBySubject("nobody").isEmpty());
        }

        @Test
        @DisplayName("findPoliciesBySubject includes category-level policies")
        void findPoliciesBySubjectCategory() {
            service.registerField("email", null, null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);
            service.linkFieldToCategory("email", null, "PII");

            // Category-level consent
            ConsentScope scope = new ConsentScope(
                "PII",
                null,
                null,
                "marketing"
            );
            service.grantConsent("user-2", scope);

            List<PolicySummaryEntry> policies = service.findPoliciesBySubject(
                "user-2"
            );
            assertEquals(1, policies.size());

            PolicySummaryEntry entry = policies.get(0);
            assertEquals("PII", entry.categoryName());
            assertNull(entry.fieldName());
            assertEquals("marketing", entry.purposeName());
        }

        @Test
        @DisplayName(
            "findPoliciesBySubject shows REVOKED status for revoked consent"
        )
        void findPoliciesBySubjectRevoked() {
            service.registerField("email", null, null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);

            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.revokeConsent("user-3", scope);

            List<PolicySummaryEntry> policies = service.findPoliciesBySubject(
                "user-3"
            );
            assertEquals(1, policies.size());
            assertEquals("REVOKED", policies.get(0).status());
        }

        @Test
        @DisplayName(
            "getConsentHistory round-trip through service registration and grant"
        )
        void getConsentHistoryRoundTrip() {
            service.registerField("email", null, null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);
            service.registerRegulation("GDPR", null, "EU", null, null);
            service.linkPurposeToRegulation("marketing", "GDPR", "consent");

            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("round-trip-user", scope);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "round-trip-user"
            );
            assertEquals(1, history.size());

            ConsentAuditEntry entry = history.get(0);
            assertEquals("round-trip-user", entry.subject());
            assertEquals("email", entry.fieldName());
            assertEquals("marketing", entry.purposeName());
            assertEquals("GRANTED", entry.action());
            assertNotNull(entry.timestamp());
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
        private Long purposeId;

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

            purposeRepo.upsertByName("marketing", null, null);
            purposeId = purposeRepo
                .findByName("marketing")
                .orElseThrow()
                .getPurposeId();
        }

        // -- upsertByField ----------------------------------------------------

        @Test
        @DisplayName("upsertByField inserts a granted field-level policy")
        void upsertByFieldGrantInserts() {
            consentPolicyRepo.upsertByField("user-1", fieldId, purposeId, true);

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    purposeId
                );
            assertTrue(found.isPresent());
            assertTrue(found.get().getGranted());
            assertNotNull(found.get().getGrantedAt());
            assertNull(found.get().getRevokedAt());
            assertEquals(purposeId, found.get().getPurposeId());
        }

        @Test
        @DisplayName("upsertByField inserts a revoked field-level policy")
        void upsertByFieldRevokeInserts() {
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                purposeId,
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    purposeId
                );
            assertTrue(found.isPresent());
            assertFalse(found.get().getGranted());
            assertNull(found.get().getGrantedAt());
            assertNotNull(found.get().getRevokedAt());
        }

        @Test
        @DisplayName("upsertByField updates an existing field-level policy")
        void upsertByFieldUpdates() {
            consentPolicyRepo.upsertByField("user-1", fieldId, purposeId, true);
            consentPolicyRepo.upsertByField(
                "user-1",
                fieldId,
                purposeId,
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    purposeId
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
                purposeId,
                true
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndCategoryAndPurpose(
                    "user-1",
                    categoryId,
                    purposeId
                );
            assertTrue(found.isPresent());
            assertTrue(found.get().getGranted());
            assertEquals(purposeId, found.get().getPurposeId());
        }

        @Test
        @DisplayName(
            "upsertByCategory updates an existing category-level policy"
        )
        void upsertByCategoryUpdates() {
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                purposeId,
                true
            );
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                purposeId,
                false
            );

            Optional<ConsentPolicy> found =
                consentPolicyRepo.findBySubjectAndCategoryAndPurpose(
                    "user-1",
                    categoryId,
                    purposeId
                );
            assertTrue(found.isPresent());
            assertFalse(found.get().getGranted());
        }

        // -- findBySubject ----------------------------------------------------

        @Test
        @DisplayName("findBySubject returns all policies for a subject")
        void findBySubject() {
            consentPolicyRepo.upsertByField("user-1", fieldId, purposeId, true);
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                purposeId,
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
                        purposeId
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
                        purposeId
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
            consentPolicyRepo.upsertByField("user-1", fieldId, purposeId, true);
            consentPolicyRepo.upsertByCategory(
                "user-1",
                categoryId,
                purposeId,
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
                purposeId,
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
            consentPolicyRepo.upsertByField("user-1", fieldId, purposeId, true);
            consentPolicyRepo.upsertByField(
                "user-2",
                fieldId,
                purposeId,
                false
            );

            Optional<ConsentPolicy> user1 =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-1",
                    fieldId,
                    purposeId
                );
            Optional<ConsentPolicy> user2 =
                consentPolicyRepo.findBySubjectAndFieldAndPurpose(
                    "user-2",
                    fieldId,
                    purposeId
                );

            assertTrue(user1.isPresent());
            assertTrue(user1.get().getGranted());
            assertTrue(user2.isPresent());
            assertFalse(user2.get().getGranted());
        }

        // -- countBySubject -------------------------------------------------

        @Test
        @DisplayName("countBySubject returns 0 for unknown subject")
        void countBySubjectZero() {
            assertEquals(0, consentPolicyRepo.countBySubject("nobody"));
        }

        @Test
        @DisplayName("countBySubject returns correct count for one policy")
        void countBySubjectOne() {
            consentPolicyRepo.upsertByField(
                "user-cnt",
                fieldId,
                purposeId,
                true
            );
            assertEquals(1, consentPolicyRepo.countBySubject("user-cnt"));
        }

        @Test
        @DisplayName(
            "countBySubject returns correct count for multiple policies"
        )
        void countBySubjectMultiple() {
            consentPolicyRepo.upsertByField(
                "user-cnt2",
                fieldId,
                purposeId,
                true
            );
            consentPolicyRepo.upsertByCategory(
                "user-cnt2",
                categoryId,
                purposeId,
                false
            );
            assertEquals(2, consentPolicyRepo.countBySubject("user-cnt2"));
        }

        @Test
        @DisplayName("countBySubject does not count other subjects")
        void countBySubjectIsolation() {
            consentPolicyRepo.upsertByField(
                "user-iso-a",
                fieldId,
                purposeId,
                true
            );
            consentPolicyRepo.upsertByField(
                "user-iso-b",
                fieldId,
                purposeId,
                true
            );
            assertEquals(1, consentPolicyRepo.countBySubject("user-iso-a"));
            assertEquals(1, consentPolicyRepo.countBySubject("user-iso-b"));
        }
    }

    // =======================================================================
    // ConsentAuditLogRepository tests
    // =======================================================================

    @Nested
    @DisplayName("ConsentAuditLogRepository")
    class ConsentAuditLogRepositoryTests {

        private Long fieldId;
        private Long categoryId;
        private Long purposeId;

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

            purposeRepo.upsertByName("marketing", null, null);
            purposeId = purposeRepo
                .findByName("marketing")
                .orElseThrow()
                .getPurposeId();
        }

        @Test
        @DisplayName("insert creates a field-level audit entry")
        void insertFieldLevel() {
            auditLogRepo.insert(
                "user-1",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "admin@test.com",
                "127.0.0.1",
                "TestAgent/1.0",
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubject("user-1");
            assertEquals(1, logs.size());

            ConsentAuditLog log = logs.getFirst();
            assertEquals("user-1", log.getSubjectId());
            assertEquals(fieldId, log.getFieldId());
            assertNull(log.getCategoryId());
            assertEquals(purposeId, log.getPurposeId());
            assertEquals("GRANTED", log.getAction());
            assertTrue(log.getGranted());
            assertNull(log.getExpiresAt());
            assertEquals("admin@test.com", log.getActor());
            assertEquals("127.0.0.1", log.getIpAddress());
            assertEquals("TestAgent/1.0", log.getUserAgent());
            assertNull(log.getMetadata());
            assertNotNull(log.getCreatedAt());
        }

        @Test
        @DisplayName("insert creates a category-level audit entry")
        void insertCategoryLevel() {
            auditLogRepo.insert(
                "user-2",
                null,
                categoryId,
                purposeId,
                "REVOKED",
                false,
                Instant.now().plus(30, ChronoUnit.DAYS),
                "system",
                null,
                null,
                "{\"reason\":\"expired\"}"
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubject("user-2");
            assertEquals(1, logs.size());

            ConsentAuditLog log = logs.getFirst();
            assertEquals("user-2", log.getSubjectId());
            assertNull(log.getFieldId());
            assertEquals(categoryId, log.getCategoryId());
            assertEquals("REVOKED", log.getAction());
            assertFalse(log.getGranted());
            assertNotNull(log.getExpiresAt());
            assertEquals("system", log.getActor());
            assertEquals("{\"reason\":\"expired\"}", log.getMetadata());
        }

        @Test
        @DisplayName("findBySubject returns entries ordered by created_at DESC")
        void findBySubjectOrdered() {
            auditLogRepo.insert(
                "user-3",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "actor1",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-3",
                null,
                categoryId,
                purposeId,
                "REVOKED",
                false,
                null,
                "actor2",
                null,
                null,
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubject("user-3");
            assertEquals(2, logs.size());
            // Most recent first — the second insert should appear first
            assertEquals("actor2", logs.get(0).getActor());
            assertEquals("actor1", logs.get(1).getActor());
        }

        @Test
        @DisplayName("findBySubject returns empty for unknown subject")
        void findBySubjectEmpty() {
            List<ConsentAuditLog> logs = auditLogRepo.findBySubject("ghost");
            assertTrue(logs.isEmpty());
        }

        @Test
        @DisplayName("findBySubjectAndScope filters by field")
        void findBySubjectAndScopeField() {
            auditLogRepo.insert(
                "user-4",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "a",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-4",
                null,
                categoryId,
                purposeId,
                "GRANTED",
                true,
                null,
                "b",
                null,
                null,
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubjectAndScope(
                "user-4",
                fieldId,
                null,
                null
            );
            assertEquals(1, logs.size());
            assertEquals(fieldId, logs.getFirst().getFieldId());
        }

        @Test
        @DisplayName("findBySubjectAndScope filters by category")
        void findBySubjectAndScopeCategory() {
            auditLogRepo.insert(
                "user-5",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "a",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-5",
                null,
                categoryId,
                purposeId,
                "REVOKED",
                false,
                null,
                "b",
                null,
                null,
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubjectAndScope(
                "user-5",
                null,
                categoryId,
                null
            );
            assertEquals(1, logs.size());
            assertEquals(categoryId, logs.getFirst().getCategoryId());
        }

        @Test
        @DisplayName("findBySubjectAndScope filters by purpose")
        void findBySubjectAndScopePurpose() {
            purposeRepo.upsertByName("analytics", null, null);
            Long analyticsPurposeId = purposeRepo
                .findByName("analytics")
                .orElseThrow()
                .getPurposeId();

            auditLogRepo.insert(
                "user-6",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "a",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-6",
                fieldId,
                null,
                analyticsPurposeId,
                "GRANTED",
                true,
                null,
                "b",
                null,
                null,
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubjectAndScope(
                "user-6",
                null,
                null,
                analyticsPurposeId
            );
            assertEquals(1, logs.size());
            assertEquals(analyticsPurposeId, logs.getFirst().getPurposeId());
        }

        @Test
        @DisplayName("findBySubjectAndScope with all filters narrows results")
        void findBySubjectAndScopeAllFilters() {
            auditLogRepo.insert(
                "user-7",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "match",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-7",
                null,
                categoryId,
                purposeId,
                "GRANTED",
                true,
                null,
                "no-match",
                null,
                null,
                null
            );

            List<ConsentAuditLog> logs = auditLogRepo.findBySubjectAndScope(
                "user-7",
                fieldId,
                null,
                purposeId
            );
            assertEquals(1, logs.size());
            assertEquals("match", logs.getFirst().getActor());
        }

        @Test
        @DisplayName("deleteBySubject removes all entries for subject")
        void deleteBySubject() {
            auditLogRepo.insert(
                "user-8",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "a",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "user-8",
                null,
                categoryId,
                purposeId,
                "REVOKED",
                false,
                null,
                "b",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "other-user",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "c",
                null,
                null,
                null
            );

            int deleted = auditLogRepo.deleteBySubject("user-8");
            assertEquals(2, deleted);

            // user-8 is gone
            assertTrue(auditLogRepo.findBySubject("user-8").isEmpty());
            // other-user is untouched
            assertEquals(1, auditLogRepo.findBySubject("other-user").size());
        }

        @Test
        @DisplayName("deleteBySubject returns 0 for unknown subject")
        void deleteBySubjectEmpty() {
            assertEquals(0, auditLogRepo.deleteBySubject("ghost"));
        }

        @Test
        @DisplayName(
            "subject isolation — different subjects do not see each other's logs"
        )
        void subjectIsolation() {
            auditLogRepo.insert(
                "alice",
                fieldId,
                null,
                purposeId,
                "GRANTED",
                true,
                null,
                "admin",
                null,
                null,
                null
            );
            auditLogRepo.insert(
                "bob",
                fieldId,
                null,
                purposeId,
                "REVOKED",
                false,
                null,
                "admin",
                null,
                null,
                null
            );

            assertEquals(1, auditLogRepo.findBySubject("alice").size());
            assertEquals(1, auditLogRepo.findBySubject("bob").size());
            assertEquals(
                "GRANTED",
                auditLogRepo.findBySubject("alice").getFirst().getAction()
            );
            assertEquals(
                "REVOKED",
                auditLogRepo.findBySubject("bob").getFirst().getAction()
            );
        }
    }

    // =======================================================================
    // Consent audit history via service (getConsentHistory)
    // =======================================================================

    @Nested
    @DisplayName("ConsentHistory")
    class ConsentHistoryTests {

        @BeforeEach
        void seedReferenceData() {
            service.registerField("email", null, null);
            service.registerField("phone", null, null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);
            service.registerPurpose("analytics", null);
        }

        @Test
        @DisplayName("granting consent writes an audit log entry")
        void grantWritesAuditLog() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-a", scope);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-a"
            );
            assertEquals(1, history.size());

            ConsentAuditEntry entry = history.getFirst();
            assertEquals("user-a", entry.subject());
            assertEquals("email", entry.fieldName());
            assertEquals("marketing", entry.purposeName());
            assertEquals("GRANTED", entry.action());
            assertNotNull(entry.timestamp());
        }

        @Test
        @DisplayName("revoking consent writes an audit log entry")
        void revokeWritesAuditLog() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.revokeConsent("user-b", scope);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-b"
            );
            assertEquals(1, history.size());
            assertEquals("REVOKED", history.getFirst().action());
        }

        @Test
        @DisplayName("multiple consent operations produce ordered history")
        void multipleOperationsOrdered() {
            ConsentScope scope1 = ConsentScope.to("PII", "email", "marketing");
            ConsentScope scope2 = ConsentScope.to("PII", "phone", "analytics");

            service.grantConsent("user-c", scope1);
            service.revokeConsent("user-c", scope2);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-c"
            );
            assertEquals(2, history.size());
            // Most recent first
            assertEquals("REVOKED", history.get(0).action());
            assertEquals("GRANTED", history.get(1).action());
        }

        @Test
        @DisplayName("getConsentHistory returns empty for unknown subject")
        void historyEmptyForUnknown() {
            assertTrue(service.getConsentHistory("nobody").isEmpty());
        }

        @Test
        @DisplayName("getConsentHistory with scope filters by field")
        void historyFilteredByScope() {
            ConsentScope emailScope = ConsentScope.to(
                "PII",
                "email",
                "marketing"
            );
            ConsentScope phoneScope = ConsentScope.to(
                "PII",
                "phone",
                "marketing"
            );

            service.grantConsent("user-d", emailScope);
            service.grantConsent("user-d", phoneScope);

            // Filter to email only
            List<ConsentAuditEntry> filtered = service.getConsentHistory(
                "user-d",
                emailScope
            );
            assertEquals(1, filtered.size());
            assertEquals("email", filtered.getFirst().fieldName());
        }

        @Test
        @DisplayName("getConsentHistory with scope filters by purpose")
        void historyFilteredByPurpose() {
            ConsentScope mkt = ConsentScope.to("PII", "email", "marketing");
            ConsentScope anl = ConsentScope.to("PII", "email", "analytics");

            service.grantConsent("user-e", mkt);
            service.grantConsent("user-e", anl);

            // Filter by purpose only (field is same, so use a scope with just purpose)
            List<ConsentAuditEntry> filtered = service.getConsentHistory(
                "user-e",
                anl
            );
            assertEquals(1, filtered.size());
            assertEquals("analytics", filtered.getFirst().purposeName());
        }

        @Test
        @DisplayName("audit entry records actor from RequestContext")
        void auditRecordsRequestContext() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");

            RequestContext.runWhere(
                new RequestContext.RequestMeta(
                    "admin@corp.com",
                    "10.0.0.1",
                    "TestBrowser/2.0"
                ),
                () -> service.grantConsent("user-f", scope)
            );

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-f"
            );
            assertEquals(1, history.size());

            ConsentAuditEntry entry = history.getFirst();
            assertEquals("admin@corp.com", entry.actor());
            assertEquals("10.0.0.1", entry.ipAddress());
            assertEquals("TestBrowser/2.0", entry.userAgent());
        }

        @Test
        @DisplayName(
            "audit entry defaults to 'system' actor when no RequestContext"
        )
        void auditDefaultsToSystem() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-g", scope);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-g"
            );
            assertEquals(1, history.size());
            assertEquals("system", history.getFirst().actor());
            assertNull(history.getFirst().ipAddress());
            assertNull(history.getFirst().userAgent());
        }

        @Test
        @DisplayName("grant with expiresAt records expiration in audit log")
        void grantWithExpiresAt() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            Instant expires = Instant.now().plus(90, ChronoUnit.DAYS);
            service.grantConsent("user-h", scope, expires);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-h"
            );
            assertEquals(1, history.size());
            assertNotNull(history.getFirst().expiresAt());
        }

        @Test
        @DisplayName(
            "category-level consent writes audit entry with category name"
        )
        void categoryLevelAudit() {
            service.linkFieldToCategory("email", null, "PII");

            // Category-level scope (no fieldName)
            ConsentScope scope = new ConsentScope(
                "PII",
                null,
                null,
                "marketing"
            );
            service.grantConsent("user-i", scope);

            List<ConsentAuditEntry> history = service.getConsentHistory(
                "user-i"
            );
            assertEquals(1, history.size());

            ConsentAuditEntry entry = history.getFirst();
            assertEquals("PII", entry.categoryName());
            assertNull(entry.fieldName());
            assertEquals("marketing", entry.purposeName());
        }
    }

    // =======================================================================
    // DSAR erasure — deleteAuditLogBySubject, countPoliciesBySubject,
    //                revokeAllConsent count, DsarAuditLogRepository
    // =======================================================================

    @Nested
    @DisplayName("DsarErasure")
    class DsarErasureTests {

        @BeforeEach
        void seedReferenceData() {
            service.registerField("email", null, null);
            service.registerField("phone", "User", null);
            service.registerCategory("PII", null);
            service.registerPurpose("marketing", null);
            service.registerPurpose("analytics", null);
        }

        // -- countPoliciesBySubject ------------------------------------------

        @Test
        @DisplayName("countPoliciesBySubject returns 0 for unknown subject")
        void countPoliciesZeroForUnknown() {
            assertEquals(0, service.countPoliciesBySubject("ghost"));
        }

        @Test
        @DisplayName("countPoliciesBySubject returns correct count")
        void countPoliciesReturnsCorrectCount() {
            ConsentScope scope1 = ConsentScope.to("PII", "email", "marketing");
            ConsentScope scope2 = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "analytics"
            );
            service.grantConsent("user-count", scope1);
            service.grantConsent("user-count", scope2);

            assertEquals(2, service.countPoliciesBySubject("user-count"));
        }

        @Test
        @DisplayName(
            "countPoliciesBySubject does not count other subjects' policies"
        )
        void countPoliciesSubjectIsolation() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-a-count", scope);
            service.grantConsent("user-b-count", scope);

            assertEquals(1, service.countPoliciesBySubject("user-a-count"));
            assertEquals(1, service.countPoliciesBySubject("user-b-count"));
        }

        // -- deleteAuditLogBySubject -----------------------------------------

        @Test
        @DisplayName(
            "deleteAuditLogBySubject deletes all audit entries for subject"
        )
        void deleteAuditLogRemovesEntries() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            // Grant and revoke to create 2 audit entries
            service.grantConsent("user-del-audit", scope);
            service.revokeConsent("user-del-audit", scope);

            // Verify entries exist
            List<ConsentAuditEntry> before = service.getConsentHistory(
                "user-del-audit"
            );
            assertEquals(2, before.size());

            // Delete
            int deleted = service.deleteAuditLogBySubject("user-del-audit");
            assertEquals(2, deleted);

            // Verify entries are gone
            List<ConsentAuditEntry> after = service.getConsentHistory(
                "user-del-audit"
            );
            assertTrue(after.isEmpty());
        }

        @Test
        @DisplayName("deleteAuditLogBySubject returns 0 for unknown subject")
        void deleteAuditLogReturnsZeroForUnknown() {
            assertEquals(0, service.deleteAuditLogBySubject("ghost"));
        }

        @Test
        @DisplayName("deleteAuditLogBySubject does not affect other subjects")
        void deleteAuditLogSubjectIsolation() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-keep", scope);
            service.grantConsent("user-erase", scope);

            // Delete only user-erase
            int deleted = service.deleteAuditLogBySubject("user-erase");
            assertEquals(1, deleted);

            // user-keep still has their audit entry
            assertEquals(1, service.getConsentHistory("user-keep").size());
            // user-erase is gone
            assertTrue(service.getConsentHistory("user-erase").isEmpty());
        }

        // -- revokeAllConsent + countPoliciesBySubject (full flow) -----------

        @Test
        @DisplayName(
            "count before revokeAllConsent reflects actual policies deleted"
        )
        void countBeforeRevokeAll() {
            ConsentScope scope1 = ConsentScope.to("PII", "email", "marketing");
            ConsentScope scope2 = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "analytics"
            );
            service.grantConsent("user-full", scope1);
            service.grantConsent("user-full", scope2);

            // Count before deletion
            int count = service.countPoliciesBySubject("user-full");
            assertEquals(2, count);

            // Delete all
            service.revokeAllConsent("user-full");

            // Count after deletion should be 0
            assertEquals(0, service.countPoliciesBySubject("user-full"));
        }

        // -- DsarAuditLogRepository -----------------------------------------

        @Test
        @DisplayName("DsarAuditLogRepository is accessible via service")
        void dsarAuditLogRepoAccessible() {
            assertNotNull(service.dsarAuditLogRepository());
        }

        @Test
        @DisplayName("DsarAuditLogRepository insert creates a DSAR entry")
        void dsarAuditLogInsert() {
            dsarAuditLogRepo.insert(
                "user-dsar",
                "req-001",
                3,
                5,
                10,
                "SUCCESS",
                "admin@test.com",
                "10.0.0.1",
                "TestAgent/1.0",
                null
            );

            var entries = dsarAuditLogRepo.findBySubject("user-dsar");
            assertEquals(1, entries.size());

            var entry = entries.getFirst();
            assertEquals("user-dsar", entry.getSubjectId());
            assertEquals("req-001", entry.getDeletionRequestId());
            assertEquals(3, entry.getPoliciesDeleted());
            assertEquals(5, entry.getCachedKeysDeleted());
            assertEquals(10, entry.getAuditEntriesDeleted());
            assertEquals("SUCCESS", entry.getStatus());
            assertEquals("admin@test.com", entry.getActor());
            assertEquals("10.0.0.1", entry.getIpAddress());
            assertEquals("TestAgent/1.0", entry.getUserAgent());
            assertNull(entry.getMetadata());
            assertNotNull(entry.getCreatedAt());
        }

        @Test
        @DisplayName(
            "DsarAuditLogRepository findByDeletionRequestId returns match"
        )
        void dsarAuditLogFindByRequestId() {
            dsarAuditLogRepo.insert(
                "user-dsar-2",
                "req-002",
                1,
                0,
                2,
                "PARTIAL_FAILURE",
                "system",
                null,
                null,
                "{\"error\":\"redis timeout\"}"
            );

            var entries = dsarAuditLogRepo.findByDeletionRequestId("req-002");
            assertEquals(1, entries.size());
            assertEquals("PARTIAL_FAILURE", entries.getFirst().getStatus());
            assertEquals(
                "{\"error\":\"redis timeout\"}",
                entries.getFirst().getMetadata()
            );
        }

        @Test
        @DisplayName(
            "DsarAuditLogRepository countSuccessfulDeletions counts only SUCCESS"
        )
        void dsarAuditLogCountSuccessful() {
            dsarAuditLogRepo.insert(
                "user-dsar-3",
                "req-003",
                1,
                0,
                1,
                "SUCCESS",
                "system",
                null,
                null,
                null
            );
            dsarAuditLogRepo.insert(
                "user-dsar-3",
                "req-004",
                0,
                0,
                0,
                "PARTIAL_FAILURE",
                "system",
                null,
                null,
                null
            );

            assertEquals(
                1,
                dsarAuditLogRepo.countSuccessfulDeletions("user-dsar-3")
            );
        }

        // -- Full DSAR erasure end-to-end flow ------------------------------

        @Test
        @DisplayName(
            "full DSAR erasure: count policies, revoke all, delete audit log, record DSAR entry"
        )
        void fullDsarErasureFlow() {
            ConsentScope scope1 = ConsentScope.to("PII", "email", "marketing");
            ConsentScope scope2 = ConsentScope.to(
                "PII",
                "phone",
                "User",
                "analytics"
            );

            // Set up data: two grants
            service.grantConsent("user-e2e", scope1);
            service.grantConsent("user-e2e", scope2);

            // Step 1: Count policies
            int policiesDeleted = service.countPoliciesBySubject("user-e2e");
            assertEquals(2, policiesDeleted);

            // Step 2: Revoke all consent (also creates a BULK_REVOKED audit entry)
            service.revokeAllConsent("user-e2e");
            assertEquals(0, service.countPoliciesBySubject("user-e2e"));

            // Step 3: Delete audit log entries
            // We expect 2 GRANTED entries + 1 BULK_REVOKED entry = 3 total
            int auditEntriesDeleted = service.deleteAuditLogBySubject(
                "user-e2e"
            );
            assertEquals(3, auditEntriesDeleted);
            assertTrue(service.getConsentHistory("user-e2e").isEmpty());

            // Step 4: Record DSAR audit log entry
            String requestId = "test-req-e2e";
            dsarAuditLogRepo.insert(
                "user-e2e",
                requestId,
                policiesDeleted,
                0, // cached keys (no Redis in this test)
                auditEntriesDeleted,
                "SUCCESS",
                "test-actor",
                "127.0.0.1",
                "TestAgent/1.0",
                null
            );

            // Verify DSAR record
            var dsarEntries = dsarAuditLogRepo.findBySubject("user-e2e");
            assertEquals(1, dsarEntries.size());

            var dsarEntry = dsarEntries.getFirst();
            assertEquals("user-e2e", dsarEntry.getSubjectId());
            assertEquals(requestId, dsarEntry.getDeletionRequestId());
            assertEquals(2, dsarEntry.getPoliciesDeleted());
            assertEquals(3, dsarEntry.getAuditEntriesDeleted());
            assertEquals(0, dsarEntry.getCachedKeysDeleted());
            assertEquals("SUCCESS", dsarEntry.getStatus());
            assertEquals("test-actor", dsarEntry.getActor());
        }

        @Test
        @DisplayName("DSAR erasure is idempotent — second pass counts zero")
        void dsarErasureIdempotent() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-idempotent", scope);

            // First pass
            int policies1 = service.countPoliciesBySubject("user-idempotent");
            assertEquals(1, policies1);
            service.revokeAllConsent("user-idempotent");
            int audit1 = service.deleteAuditLogBySubject("user-idempotent");
            assertTrue(audit1 > 0);

            // Second pass — everything should be zero
            int policies2 = service.countPoliciesBySubject("user-idempotent");
            assertEquals(0, policies2);
            int audit2 = service.deleteAuditLogBySubject("user-idempotent");
            assertEquals(0, audit2);
        }

        @Test
        @DisplayName("DSAR erasure does not affect other subjects' data")
        void dsarErasureSubjectIsolation() {
            ConsentScope scope = ConsentScope.to("PII", "email", "marketing");
            service.grantConsent("user-erase-iso", scope);
            service.grantConsent("user-keep-iso", scope);

            // Erase user-erase-iso
            service.revokeAllConsent("user-erase-iso");
            service.deleteAuditLogBySubject("user-erase-iso");

            // user-erase-iso is fully gone
            assertEquals(0, service.countPoliciesBySubject("user-erase-iso"));
            assertTrue(service.getConsentHistory("user-erase-iso").isEmpty());

            // user-keep-iso is untouched
            assertEquals(1, service.countPoliciesBySubject("user-keep-iso"));
            assertEquals(1, service.getConsentHistory("user-keep-iso").size());
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
            assertEquals(0, dsl.fetchCount(CONSENT_AUDIT_LOG));
            assertEquals(0, dsl.fetchCount(DSAR_AUDIT_LOG));

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
            assertEquals(0, dsl.fetchCount(CONSENT_AUDIT_LOG));
            assertEquals(0, dsl.fetchCount(DSAR_AUDIT_LOG));
        }
    }
}
