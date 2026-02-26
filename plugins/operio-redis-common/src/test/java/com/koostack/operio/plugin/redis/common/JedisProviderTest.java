package com.koostack.operio.plugin.redis.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

/**
 * Unit tests for the {@link JedisProvider} functional interface and its
 * {@link JedisProvider#shared(JedisPooled)} factory method.
 */
@DisplayName("JedisProvider")
class JedisProviderTest {

    // -----------------------------------------------------------------------
    // shared() factory
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("shared()")
    class SharedProvider {

        @Test
        @DisplayName("returns the same JedisPooled instance on every call")
        void returnsSameInstance() {
            // Use a dummy host/port — we never actually connect
            JedisPooled pool = new JedisPooled("localhost", 16379);
            JedisProvider provider = JedisProvider.shared(pool);

            assertSame(pool, provider.get(), "First call should return the same instance");
            assertSame(pool, provider.get(), "Second call should return the same instance");
            assertSame(pool, provider.get(), "Third call should return the same instance");

            pool.close();
        }

        @Test
        @DisplayName("throws NullPointerException when jedis argument is null")
        void nullJedisThrows() {
            NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> JedisProvider.shared(null)
            );
            assertTrue(
                ex.getMessage().contains("must not be null"),
                "Exception message should mention null: " + ex.getMessage()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Functional interface contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("functional interface")
    class FunctionalInterface {

        @Test
        @DisplayName("can be implemented as a lambda")
        void lambdaImplementation() {
            JedisPooled pool = new JedisPooled("localhost", 16379);
            JedisProvider provider = () -> pool;

            assertSame(pool, provider.get());

            pool.close();
        }

        @Test
        @DisplayName("can be implemented as a method reference")
        void methodReferenceImplementation() {
            JedisPooled pool = new JedisPooled("localhost", 16379);
            PoolHolder holder = new PoolHolder(pool);

            JedisProvider provider = holder::getPool;
            assertSame(pool, provider.get());

            pool.close();
        }

        @Test
        @DisplayName("different shared providers for different pools return distinct instances")
        void distinctProviders() {
            JedisPooled pool1 = new JedisPooled("localhost", 16379);
            JedisPooled pool2 = new JedisPooled("localhost", 16380);

            JedisProvider provider1 = JedisProvider.shared(pool1);
            JedisProvider provider2 = JedisProvider.shared(pool2);

            assertSame(pool1, provider1.get());
            assertSame(pool2, provider2.get());
            assertNotSame(provider1.get(), provider2.get());

            pool1.close();
            pool2.close();
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /** Simple holder to test method-reference implementation of JedisProvider. */
    private record PoolHolder(JedisPooled pool) {
        JedisPooled getPool() {
            return pool;
        }
    }
}
