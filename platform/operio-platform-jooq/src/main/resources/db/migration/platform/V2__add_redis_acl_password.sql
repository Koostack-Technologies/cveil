-- ============================================================================
-- V2: Add redis_acl_password column to tenant table
-- ============================================================================
-- The existing redis_acl_pass_hash column stores a SHA-256 hash of the
-- Redis ACL password — useful for auditing but NOT usable for authenticating
-- as the tenant's Redis ACL user (Redis needs the plaintext password on
-- AUTH, not the hash).
--
-- This migration adds a redis_acl_password column that stores the actual
-- password so that the application can create per-tenant JedisPooled
-- connections authenticated as the tenant's ACL user, enforcing Redis-level
-- key isolation (not just application-level key-prefix isolation).
--
-- SECURITY NOTE: In production, this column SHOULD be encrypted at rest
-- using Postgres pgcrypto, application-level envelope encryption, or a
-- secrets manager (Vault, AWS Secrets Manager, etc.). The plaintext column
-- is a pragmatic starting point — the password is already a random 32-char
-- base64url token with no reuse risk, and the platform DB itself should be
-- encrypted at rest and access-controlled. A future migration can convert
-- this to an encrypted column or move it to an external secrets store.
-- ============================================================================

ALTER TABLE tenant ADD COLUMN redis_acl_password VARCHAR(255);

-- Backfill note: existing tenants provisioned before this migration will
-- have redis_acl_password = NULL. The TenantManager.reprovisionRedisAcl()
-- method (or a manual ACL reset) can be used to generate a new password,
-- update both the Redis ACL and this column, for any tenant that needs
-- per-tenant Redis connection isolation retroactively.
