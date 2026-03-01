package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IdpProvider;

import java.util.logging.Logger;

/**
 * Default {@link PlatformService} implementation that delegates IAM
 * operations to the configured {@link IdpProvider}.
 *
 * <p>All methods take an explicit {@code tenantId} — unlike
 * {@link DefaultTenantService} which uses {@code TenantContext}.
 */
public class DefaultPlatformService implements PlatformService {

    private static final Logger LOG = Logger.getLogger(
        DefaultPlatformService.class.getName()
    );

    private final String appName;
    private final IdpProvider<?> idpProvider;

    public DefaultPlatformService(String appName, IdpProvider<?> idpProvider) {
        if (idpProvider == null) {
            throw new OperioConfigurationException(
                "DefaultPlatformService requires a non-null IdpProvider"
            );
        }
        this.appName = appName != null ? appName : Operio.DEFAULT_APP_NAME;
        this.idpProvider = idpProvider;
    }

    // -----------------------------------------------------------------------
    // OperioService
    // -----------------------------------------------------------------------

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public boolean isReady() {
        return idpProvider.isReady();
    }

    @Override
    public void verifyReady() {
        if (!isReady()) {
            throw new OperioConfigurationException(
                "PlatformService IdpProvider '" + idpProvider.getName() + "' is not ready"
            );
        }
    }

    // -----------------------------------------------------------------------
    // IAM — User Management
    // -----------------------------------------------------------------------

    @Override
    public IdpUser registerUser(String tenantId, String email, String password, String fullName) {
        return idpProvider.registerUser(tenantId, email, password, fullName);
    }

    @Override
    public void verifyEmail(String tenantId, String userId, String verificationToken) {
        idpProvider.verifyEmail(tenantId, userId, verificationToken);
    }

    @Override
    public void initiatePasswordReset(String tenantId, String email) {
        idpProvider.initiatePasswordReset(tenantId, email);
    }

    @Override
    public void completePasswordReset(String tenantId, String resetToken, String newPassword) {
        idpProvider.completePasswordReset(tenantId, resetToken, newPassword);
    }
}
