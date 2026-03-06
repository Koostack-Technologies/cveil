package com.koostack.operio.platform.tenant.jooq;

/**
 * Lightweight tenant data transfer object used by {@link JooqTenantManagerService} for bulk
 * operations.
 */
public class TenantRecord {

    private String tenantId;
    private String status;
    private String schemaName;
    private String redisAclPassword;

    public TenantRecord() {}

    public TenantRecord(
            String tenantId, String status, String schemaName, String redisAclPassword) {
        this.tenantId = tenantId;
        this.status = status;
        this.schemaName = schemaName;
        this.redisAclPassword = redisAclPassword;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getRedisAclPassword() {
        return redisAclPassword;
    }

    public void setRedisAclPassword(String redisAclPassword) {
        this.redisAclPassword = redisAclPassword;
    }
}
