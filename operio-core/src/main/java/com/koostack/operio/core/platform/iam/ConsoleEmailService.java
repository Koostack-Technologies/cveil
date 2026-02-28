package com.koostack.operio.core.platform.iam;

import java.util.logging.Logger;

/**
 * Default {@link EmailService} implementation that logs emails to the
 * console rather than sending them.
 *
 * <p>Intended for development and testing. The verification/reset links
 * are logged at INFO level so developers can copy them from the console.
 */
public class ConsoleEmailService implements EmailService {

    private static final Logger LOG = Logger.getLogger(ConsoleEmailService.class.getName());

    @Override
    public void sendVerificationEmail(String email, String verificationLink) {
        LOG.info(
            "\n===== EMAIL VERIFICATION =====\n" +
            "  To:   " + email + "\n" +
            "  Link: " + verificationLink + "\n" +
            "=============================="
        );
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetLink) {
        LOG.info(
            "\n===== PASSWORD RESET =====\n" +
            "  To:   " + email + "\n" +
            "  Link: " + resetLink + "\n" +
            "=========================="
        );
    }
}
