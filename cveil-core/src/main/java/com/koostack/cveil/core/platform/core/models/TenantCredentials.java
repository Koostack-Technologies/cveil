package com.koostack.cveil.core.platform.core.models;

/**
 * Redis ACL credentials for a tenant.
 *
 * @param redisAclUser the Redis ACL username (e.g. {@code "t_acme"})
 * @param redisAclPassword the decrypted plaintext Redis ACL password
 */
public record TenantCredentials(String redisAclUser, String redisAclPassword) {}
