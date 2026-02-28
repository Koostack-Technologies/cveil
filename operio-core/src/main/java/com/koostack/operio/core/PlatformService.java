package com.koostack.operio.core;

/**
 * Platform-scoped application service for cross-tenant operations.
 *
 * <p>Currently a stub — platform operations (tenant provisioning, metrics,
 * IAM) are handled by dedicated platform plugins. This interface exists as
 * the platform-side counterpart to {@link TenantService} and will gain
 * methods as the platform domain grows.
 */
public interface PlatformService extends OperioService {
}
