package com.koostack.operio.plugin.consent.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.JedisPooled;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConsentKeyCleanupService")
class ConsentKeyCleanupServiceTest {

    private RedisServer redisServer;
    private JedisPooled jedis;
    private ConsentKeyCleanupService cleanup;

    @BeforeAll
    void setUpServer() throws IOException {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(redisServer.getHost(), redisServer.getBindPort());
        cleanup = new ConsentKeyCleanupService(JedisProvider.shared(jedis));
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
    @DisplayName("deletes field2cat keys for a tenant")
    void deletesField2CatKeys() {
        jedis.sadd("operio:t1:meta:field2cat:_default_/ssn", "PII");
        jedis.sadd("operio:t1:meta:field2cat:User/email", "Contact");

        int deleted = cleanup.cleanup("t1", "operio");

        assertEquals(2, deleted);
        assertFalse(jedis.exists("operio:t1:meta:field2cat:_default_/ssn"));
        assertFalse(jedis.exists("operio:t1:meta:field2cat:User/email"));
    }

    @Test
    @DisplayName("deletes cat2field keys for a tenant")
    void deletesCat2FieldKeys() {
        jedis.sadd("operio:t1:meta:cat2field:PII", "_default_/ssn", "_default_/email");
        jedis.sadd("operio:t1:meta:cat2field:Contact", "User/phone");

        int deleted = cleanup.cleanup("t1", "operio");

        assertEquals(2, deleted);
        assertFalse(jedis.exists("operio:t1:meta:cat2field:PII"));
        assertFalse(jedis.exists("operio:t1:meta:cat2field:Contact"));
    }

    @Test
    @DisplayName("does not delete non-metadata keys")
    void doesNotDeleteNonMetadataKeys() {
        // New-format consent hash — should NOT be touched
        jedis.hset("t1:consent:user-1", "f:_default_/ssn:marketing", "GRANTED");
        // Metadata key — should be deleted
        jedis.sadd("operio:t1:meta:field2cat:_default_/ssn", "PII");

        int deleted = cleanup.cleanup("t1", "operio");

        assertEquals(1, deleted);
        assertTrue(jedis.exists("t1:consent:user-1"));
        assertEquals("GRANTED", jedis.hget("t1:consent:user-1", "f:_default_/ssn:marketing"));
    }

    @Test
    @DisplayName("cleanupAll processes multiple tenants")
    void cleanupAllProcessesMultipleTenants() {
        jedis.sadd("operio:t1:meta:field2cat:_default_/ssn", "PII");
        jedis.sadd("operio:t2:meta:cat2field:PII", "_default_/ssn");

        int total = cleanup.cleanupAll(List.of("t1", "t2"), "operio");

        assertEquals(2, total);
    }

    @Test
    @DisplayName("cleanupLegacy deletes single-tenant metadata keys")
    void cleanupLegacyKeys() {
        jedis.sadd("operio:meta:field2cat:_default_/ssn", "PII");
        jedis.sadd("operio:meta:cat2field:PII", "_default_/ssn");

        int deleted = cleanup.cleanupLegacy("operio");

        assertEquals(2, deleted);
        assertFalse(jedis.exists("operio:meta:field2cat:_default_/ssn"));
        assertFalse(jedis.exists("operio:meta:cat2field:PII"));
    }

    @Test
    @DisplayName("no-op when no orphaned keys exist")
    void noOpWhenNoOrphanedKeys() {
        int deleted = cleanup.cleanup("t1", "operio");
        assertEquals(0, deleted);
    }

    @Test
    @DisplayName("does not touch keys from other tenants")
    void doesNotTouchOtherTenants() {
        jedis.sadd("operio:t1:meta:field2cat:_default_/ssn", "PII");
        jedis.sadd("operio:t2:meta:field2cat:_default_/ssn", "PII");

        int deleted = cleanup.cleanup("t1", "operio");

        assertEquals(1, deleted);
        assertTrue(jedis.exists("operio:t2:meta:field2cat:_default_/ssn"));
    }
}
