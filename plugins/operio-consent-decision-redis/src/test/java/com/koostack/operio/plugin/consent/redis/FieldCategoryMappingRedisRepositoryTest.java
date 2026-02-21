package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.JedisPooled;

/**
 * Integration tests for {@link FieldCategoryMappingRedisRepository} backed by
 * an embedded {@link RedisServer} (jedis-mock) — no Docker required.
 *
 * <p>Each {@code @Nested} class groups related scenarios. {@code @BeforeEach}
 * flushes the entire Redis database so every test starts from a clean slate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FieldCategoryMappingRedisRepository")
class FieldCategoryMappingRedisRepositoryTest {

    private RedisServer redisServer;
    private JedisPooled jedis;
    private FieldCategoryMappingRedisRepository repo;

    @BeforeAll
    void setUpServer() throws IOException {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(
            redisServer.getHost(),
            redisServer.getBindPort()
        );
        repo = new FieldCategoryMappingRedisRepository(jedis);
    }

    @AfterAll
    void tearDownServer() throws IOException {
        if (jedis != null) {
            jedis.close();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void flushRedis() {
        jedis.flushAll();
    }

    // -----------------------------------------------------------------------
    // Link
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("addFieldToCategory")
    class AddFieldToCategory {

        @Test
        @DisplayName("creates a bidirectional mapping")
        void createsMapping() {
            repo.addFieldToCategory("ssn", "PII");

            Set<String> categories = repo.getCategoriesForField("ssn");
            Set<String> fields = repo.getFieldsForCategory("PII");

            assertEquals(Set.of("PII"), categories);
            assertEquals(Set.of("ssn"), fields);
        }

        @Test
        @DisplayName("is idempotent — calling twice does not duplicate")
        void idempotent() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("ssn", "PII");

            Set<String> categories = repo.getCategoriesForField("ssn");
            Set<String> fields = repo.getFieldsForCategory("PII");

            assertEquals(1, categories.size());
            assertEquals(1, fields.size());
        }

        @Test
        @DisplayName("one field can belong to multiple categories")
        void fieldMultipleCategories() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("ssn", "Sensitive");
            repo.addFieldToCategory("ssn", "Financial");

            Set<String> categories = repo.getCategoriesForField("ssn");
            assertEquals(Set.of("PII", "Sensitive", "Financial"), categories);

            // Each category should contain ssn
            assertEquals(Set.of("ssn"), repo.getFieldsForCategory("PII"));
            assertEquals(Set.of("ssn"), repo.getFieldsForCategory("Sensitive"));
            assertEquals(Set.of("ssn"), repo.getFieldsForCategory("Financial"));
        }

        @Test
        @DisplayName("one category can contain multiple fields")
        void categoryMultipleFields() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("email", "PII");
            repo.addFieldToCategory("phone", "PII");

            Set<String> fields = repo.getFieldsForCategory("PII");
            assertEquals(Set.of("ssn", "email", "phone"), fields);

            // Each field should list PII as a category
            assertEquals(Set.of("PII"), repo.getCategoriesForField("ssn"));
            assertEquals(Set.of("PII"), repo.getCategoriesForField("email"));
            assertEquals(Set.of("PII"), repo.getCategoriesForField("phone"));
        }

        @Test
        @DisplayName("many-to-many: multiple fields across multiple categories")
        void manyToMany() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("ssn", "Financial");
            repo.addFieldToCategory("email", "PII");
            repo.addFieldToCategory("account_no", "Financial");

            assertEquals(
                Set.of("PII", "Financial"),
                repo.getCategoriesForField("ssn")
            );
            assertEquals(Set.of("PII"), repo.getCategoriesForField("email"));
            assertEquals(
                Set.of("Financial"),
                repo.getCategoriesForField("account_no")
            );

            assertEquals(
                Set.of("ssn", "email"),
                repo.getFieldsForCategory("PII")
            );
            assertEquals(
                Set.of("ssn", "account_no"),
                repo.getFieldsForCategory("Financial")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Unlink
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeFieldFromCategory")
    class RemoveFieldFromCategory {

        @Test
        @DisplayName("removes a single mapping from both directions")
        void removesSingleMapping() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("email", "PII");

            repo.removeFieldFromCategory("ssn", "PII");

            assertFalse(repo.isMapped("ssn", "PII"));
            assertTrue(repo.isMapped("email", "PII"));

            assertTrue(repo.getCategoriesForField("ssn").isEmpty());
            assertEquals(Set.of("email"), repo.getFieldsForCategory("PII"));
        }

        @Test
        @DisplayName("is a no-op when the mapping does not exist")
        void noOpWhenMissing() {
            assertDoesNotThrow(() ->
                repo.removeFieldFromCategory("ssn", "PII")
            );
        }

        @Test
        @DisplayName(
            "removes only the targeted category, leaving others intact"
        )
        void removesOneCategory() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("ssn", "Sensitive");

            repo.removeFieldFromCategory("ssn", "PII");

            assertEquals(
                Set.of("Sensitive"),
                repo.getCategoriesForField("ssn")
            );
            assertTrue(repo.getFieldsForCategory("PII").isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName(
            "getCategoriesForField returns empty set for unknown field"
        )
        void categoriesForUnknownField() {
            Set<String> result = repo.getCategoriesForField("unknown");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName(
            "getFieldsForCategory returns empty set for unknown category"
        )
        void fieldsForUnknownCategory() {
            Set<String> result = repo.getFieldsForCategory("unknown");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("isMapped returns true for existing mapping")
        void isMappedTrue() {
            repo.addFieldToCategory("ssn", "PII");
            assertTrue(repo.isMapped("ssn", "PII"));
        }

        @Test
        @DisplayName("isMapped returns false for non-existent mapping")
        void isMappedFalse() {
            assertFalse(repo.isMapped("ssn", "PII"));
        }

        @Test
        @DisplayName("isMapped returns false after removal")
        void isMappedAfterRemoval() {
            repo.addFieldToCategory("ssn", "PII");
            repo.removeFieldFromCategory("ssn", "PII");
            assertFalse(repo.isMapped("ssn", "PII"));
        }
    }

    // -----------------------------------------------------------------------
    // Bulk removal — by field
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeAllMappingsForField")
    class RemoveAllMappingsForField {

        @Test
        @DisplayName(
            "removes field from all its categories and deletes its own set"
        )
        void removesFromAllCategories() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("ssn", "Sensitive");
            repo.addFieldToCategory("ssn", "Financial");
            // Another field in PII should remain
            repo.addFieldToCategory("email", "PII");

            int removed = repo.removeAllMappingsForField("ssn");

            assertEquals(3, removed);
            assertTrue(repo.getCategoriesForField("ssn").isEmpty());
            assertFalse(repo.isMapped("ssn", "PII"));
            assertFalse(repo.isMapped("ssn", "Sensitive"));
            assertFalse(repo.isMapped("ssn", "Financial"));

            // email should still be in PII
            assertEquals(Set.of("email"), repo.getFieldsForCategory("PII"));
            // Sensitive and Financial should now be empty
            assertTrue(repo.getFieldsForCategory("Sensitive").isEmpty());
            assertTrue(repo.getFieldsForCategory("Financial").isEmpty());
        }

        @Test
        @DisplayName("returns 0 for an unknown field")
        void unknownFieldReturnsZero() {
            int removed = repo.removeAllMappingsForField("unknown");
            assertEquals(0, removed);
        }

        @Test
        @DisplayName("returns 0 for a field with no mappings after removal")
        void alreadyClearedFieldReturnsZero() {
            repo.addFieldToCategory("ssn", "PII");
            repo.removeFieldFromCategory("ssn", "PII");

            int removed = repo.removeAllMappingsForField("ssn");
            assertEquals(0, removed);
        }
    }

    // -----------------------------------------------------------------------
    // Bulk removal — by category
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeAllMappingsForCategory")
    class RemoveAllMappingsForCategory {

        @Test
        @DisplayName(
            "removes category from all its fields and deletes its own set"
        )
        void removesFromAllFields() {
            repo.addFieldToCategory("ssn", "PII");
            repo.addFieldToCategory("email", "PII");
            repo.addFieldToCategory("phone", "PII");
            // ssn is also in Sensitive — should remain
            repo.addFieldToCategory("ssn", "Sensitive");

            int removed = repo.removeAllMappingsForCategory("PII");

            assertEquals(3, removed);
            assertTrue(repo.getFieldsForCategory("PII").isEmpty());
            assertFalse(repo.isMapped("ssn", "PII"));
            assertFalse(repo.isMapped("email", "PII"));
            assertFalse(repo.isMapped("phone", "PII"));

            // ssn should still be in Sensitive
            assertEquals(
                Set.of("Sensitive"),
                repo.getCategoriesForField("ssn")
            );
            // email and phone should now have no categories
            assertTrue(repo.getCategoriesForField("email").isEmpty());
            assertTrue(repo.getCategoriesForField("phone").isEmpty());
        }

        @Test
        @DisplayName("returns 0 for an unknown category")
        void unknownCategoryReturnsZero() {
            int removed = repo.removeAllMappingsForCategory("unknown");
            assertEquals(0, removed);
        }

        @Test
        @DisplayName("returns 0 for a category with no mappings after removal")
        void alreadyClearedCategoryReturnsZero() {
            repo.addFieldToCategory("ssn", "PII");
            repo.removeFieldFromCategory("ssn", "PII");

            int removed = repo.removeAllMappingsForCategory("PII");
            assertEquals(0, removed);
        }
    }

    // -----------------------------------------------------------------------
    // Key formatting
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Key formatting")
    class KeyFormatting {

        @Test
        @DisplayName("fieldToCatKey uses correct prefix")
        void fieldToCatKey() {
            assertEquals(
                "operio:meta:field2cat:ssn",
                FieldCategoryMappingRedisRepository.fieldToCatKey("ssn")
            );
        }

        @Test
        @DisplayName("catToFieldKey uses correct prefix")
        void catToFieldKey() {
            assertEquals(
                "operio:meta:cat2field:PII",
                FieldCategoryMappingRedisRepository.catToFieldKey("PII")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Test isolation verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Test isolation")
    class TestIsolationCheck {

        @Test
        @DisplayName("Redis is empty at test start — run 1")
        void emptyRun1() {
            assertFalse(repo.isMapped("ssn", "PII"));
        }

        @Test
        @DisplayName("Redis is empty at test start — run 2")
        void emptyRun2() {
            // Write something — next test should still see empty DB
            repo.addFieldToCategory("ssn", "PII");
            assertTrue(repo.isMapped("ssn", "PII"));
        }

        @Test
        @DisplayName(
            "Redis is empty at test start — run 3 (proves run 2 was cleaned)"
        )
        void emptyRun3() {
            assertFalse(repo.isMapped("ssn", "PII"));
        }
    }
}
