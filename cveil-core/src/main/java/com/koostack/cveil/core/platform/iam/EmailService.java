package com.koostack.cveil.core.platform.iam;

/**
 * Pluggable email delivery service for identity-related emails (verification, password reset).
 *
 * <p>The default implementation ({@link ConsoleEmailService}) logs emails to the console for
 * development and testing. Production deployments should provide a real implementation (SMTP,
 * SendGrid, SES, etc.) via CDI or the plugin SPI.
 *
 * @see ConsoleEmailService
 */
public interface EmailService {

    /**
     * Send an email verification link to the given address.
     *
     * @param email the recipient email address
     * @param verificationLink the full URL the user should click to verify
     */
    void sendVerificationEmail(String email, String verificationLink);

    /**
     * Send a password reset link to the given address.
     *
     * @param email the recipient email address
     * @param resetLink the full URL the user should click to reset their password
     */
    void sendPasswordResetEmail(String email, String resetLink);
}
