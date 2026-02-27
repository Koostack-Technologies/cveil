package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.JedisPooled;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConsentKeyMigrationService")
class ConsentKeyMigrationServiceTest {

    private RedisServer redisServer;
    private JedisPooled jedis;
    private JedisProvider provider;
    private ConsentPolicyRedisRepository repo;
    private ConsentKeyMigrationService migration;

    @BeforeAll
    void setUpServer() throws IOException {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(redisServer.getHost(), redisServer.getBindPort());
        provider = JedisProvider.shared(jedis);
        repo = new ConsentPolicyRedisRepository(provider);
        migration = new ConsentKeyMigrationService(provider, repo, "operio");
    }

    @AfterAll
    void tearDownServer() throws IOException {
        if (jedis != null) jedis.close();
        if (redisServer != null) redisServer.stop();
    }

    @BeforeEach
    void flushRedis() {
        jedis.flushAll();
    }

    @Test
    @DisplayName("migrates FIELD-source flat keys to Hash format")
    void migratesFieldSourceKeys() throws Exception {
        // Set up old flat keys
        jedis.set("operio:t1:consent:user-1:_default_/ssn:marketing", "GRANTED|FIELD");
        jedis.set("operio:t1:consent:user-1:User/email:analytics", "REVOKED|FIELD");

        int migrated = TenantContext.callWhere("t1", () -> migration.migrate("t1"));

        assertEquals(2, migrated);

        // Verify new Hash entries
        TenantContext.callWhere("t1", () -> {
            Map<String, String> hash = jedis.hgetAll("t1:consent:user-1");
            assertEquals("GRANTED", hash.get("f:_default_/ssn:marketing"));
            assertEquals("REVOKED", hash.get("f:User/email:analytics"));
            return null;
        });

        // Verify old keys deleted
        assertNull(jedis.get("operio:t1:consent:user-1:_default_/ssn:marketing"));
        assertNull(jedis.get("operio:t1:consent:user-1:User/email:analytics"));
    }

    @Test
    @DisplayName("skips and deletes CATEGORY-source flat keys")
    void skipsCategorySourceKeys() throws Exception {
        jedis.set("operio:t1:consent:user-1:_default_/ssn:marketing", "GRANTED|CATEGORY");

        int migrated = TenantContext.callWhere("t1", () -> migration.migrate("t1"));

        assertEquals(0, migrated);

        // Old key should be deleted
        assertNull(jedis.get("operio:t1:consent:user-1:_default_/ssn:marketing"));

        // No new Hash should be created
        TenantContext.callWhere("t1", () -> {
            assertFalse(jedis.exists("t1:consent:user-1"));
            return null;
        });
    }

    @Test
    @DisplayName("handles mixed FIELD and CATEGORY sources")
    void handlesMixedSources() throws Exception {
        jedis.set("operio:t1:consent:user-1:_default_/ssn:marketing", "GRANTED|FIELD");
        jedis.set("operio:t1:consent:user-1:_default_/email:marketing", "GRANTED|CATEGORY");
        jedis.set("operio:t1:consent:user-1:User/phone:analytics", "REVOKED|FIELD");

        int migrated = TenantContext.callWhere("t1", () -> migration.migrate("t1"));

        assertEquals(2, migrated);

        TenantContext.callWhere("t1", () -> {
            Map<String, String> hash = jedis.hgetAll("t1:consent:user-1");
            assertEquals(2, hash.size());
            assertEquals("GRANTED", hash.get("f:_default_/ssn:marketing"));
            assertEquals("REVOKED", hash.get("f:User/phone:analytics"));
            // Category entry should NOT have been migrated
            assertNull(hash.get("f:_default_/email:marketing"));
            return null;
        });
    }

    @Test
    @DisplayName("migrateAll processes multiple tenants")
    void migrateAllProcessesMultipleTenants() throws Exception {
        jedis.set("operio:t1:consent:user-1:_default_/ssn:marketing", "GRANTED|FIELD");
        jedis.set("operio:t2:consent:user-2:User/email:analytics", "REVOKED|FIELD");

        int total = migration.migrateAll(List.of("t1", "t2"));

        assertEquals(2, total);

        // Verify tenant 1
        TenantContext.callWhere("t1", () -> {
            assertEquals("GRANTED", jedis.hget("t1:consent:user-1", "f:_default_/ssn:marketing"));
            return null;
        });

        // Verify tenant 2
        TenantContext.callWhere("t2", () -> {
            assertEquals("REVOKED", jedis.hget("t2:consent:user-2", "f:User/email:analytics"));
            return null;
        });
    }

    @Test
    @DisplayName("no-op when no old keys exist")
    void noOpWhenNoOldKeys() throws Exception {
        int migrated = TenantContext.callWhere("t1", () -> migration.migrate("t1"));
        assertEquals(0, migrated);
    }

    @Test
    @DisplayName("skips malformed values gracefully")
    void skipsMalformedValues() throws Exception {
        jedis.set("operio:t1:consent:user-1:_default_/ssn:marketing", "BADVALUE");

        int migrated = TenantContext.callWhere("t1", () -> migration.migrate("t1"));
        assertEquals(0, migrated);
    }

    // -----------------------------------------------------------------------
    // Parser unit tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseOldKey extracts subject, qualifiedField, purpose")
    void parseOldKey() {
        var result = migration.parseOldKey(
            "operio:t1:consent:user-1:_default_/ssn:marketing", "t1"
        );
        assertNotNull(result);
        assertEquals("user-1", result.subject());
        assertEquals("_default_/ssn", result.qualifiedField());
        assertEquals("marketing", result.purpose());
    }

    @Test
    @DisplayName("parseOldKey handles entity-qualified fields")
    void parseOldKeyWithEntity() {
        var result = migration.parseOldKey(
            "operio:t1:consent:user-1:User/email:analytics", "t1"
        );
        assertNotNull(result);
        assertEquals("user-1", result.subject());
        assertEquals("User/email", result.qualifiedField());
        assertEquals("analytics", result.purpose());
    }

    @Test
    @DisplayName("parseOldKey returns null for malformed keys")
    void parseOldKeyMalformed() {
        assertNull(migration.parseOldKey("operio:t1:consent:", "t1"));
        assertNull(migration.parseOldKey("wrongprefix:t1:consent:u:f:p", "t1"));
    }

    @Test
    @DisplayName("parseOldValue splits status and source")
    void parseOldValue() {
        var result = migration.parseOldValue("GRANTED|FIELD");
        assertNotNull(result);
        assertEquals("GRANTED", result.status());
        assertEquals("FIELD", result.source());
    }

    @Test
    @DisplayName("parseOldValue returns null for malformed values")
    void parseOldValueMalformed() {
        assertNull(migration.parseOldValue("BADVALUE"));
        assertNull(migration.parseOldValue("|FIELD"));
        assertNull(migration.parseOldValue("GRANTED|"));
    }

    @Test
    @DisplayName("splitQualifiedField separates entity and field")
    void splitQualifiedField() {
        String[] parts = ConsentKeyMigrationService.splitQualifiedField("User/email");
        assertEquals("User", parts[0]);
        assertEquals("email", parts[1]);
    }

    @Test
    @DisplayName("splitQualifiedField handles bare field name without separator")
    void splitQualifiedFieldNoSeparator() {
        String[] parts = ConsentKeyMigrationService.splitQualifiedField("ssn");
        assertNull(parts[0]);
        assertEquals("ssn", parts[1]);
    }

    @Test
    @DisplayName("splitQualifiedField handles leading separator")
    void splitQualifiedFieldLeadingSeparator() {
        String[] parts = ConsentKeyMigrationService.splitQualifiedField("/email");
        assertNull(parts[0]);
        assertEquals("email", parts[1]);
    }

    @Test
    @DisplayName("parseOldValue rejects unknown status/source values")
    void parseOldValueRejectsUnknown() {
        assertNull(migration.parseOldValue("UNKNOWN|FIELD"));
        assertNull(migration.parseOldValue("GRANTED|INVALID"));
        assertNull(migration.parseOldValue("MAYBE|OTHER"));
    }
}
