package com.koostack.cveil.core.platform.iam;

import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.models.IdpUser;

/**
 * Sentinel {@link IdpProvider} that throws {@link UnsupportedOperationException} on every method.
 *
 * <p>Used as a null-safe placeholder for CDI {@code @ApplicationScoped} producers that must not
 * return {@code null}.
 */
enum UnavailableIdpProvider implements IdpProvider<Void> {
    INSTANCE;

    private static final String MSG = "No identity provider configured";

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public AuthResult authenticate(String tenantId, String email, String password) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void logout(String tenantId, String refreshToken) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public IdpUser registerUser(String tenantId, String email, String password, String fullName) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void verifyEmail(String tenantId, String userId, String verificationToken) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void initiatePasswordReset(String tenantId, String email) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void completePasswordReset(String tenantId, String resetToken, String newPassword) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public IdentityContext verifyToken(String tenantId, String accessToken) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public Class<Void> getConfigurationType() {
        return Void.class;
    }

    @Override
    public void configure(Void config) {
        // no-op
    }

    @Override
    public String getName() {
        return "unavailable";
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
