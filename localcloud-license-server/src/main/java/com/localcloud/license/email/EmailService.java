package com.localcloud.license.email;

import com.localcloud.license.LicenseServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final LicenseServerConfig config;
    private final boolean devMode;

    public EmailService(LicenseServerConfig config) {
        this.config = config;
        this.devMode = config.getSmtpHost().equals("localhost") && config.getSmtpUser().isBlank();
    }

    public void sendOtp(String email, String otp) {
        if (devMode) {
            logger.info("DEV MODE — OTP for {}: {}", email, otp);
            return;
        }
        // Production SMTP sending via JavaMail
        try {
            java.util.Properties props = new java.util.Properties();
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.starttls.enable", "true");
            if (!config.getSmtpUser().isBlank()) {
                props.put("mail.smtp.auth", "true");
            }

            jakarta.mail.Session session = jakarta.mail.Session.getInstance(props,
                config.getSmtpUser().isBlank() ? null :
                new jakarta.mail.Authenticator() {
                    @Override
                    protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new jakarta.mail.PasswordAuthentication(
                            config.getSmtpUser(), config.getSmtpPassword());
                    }
                });

            jakarta.mail.Message msg = new jakarta.mail.internet.MimeMessage(session);
            msg.setFrom(new jakarta.mail.internet.InternetAddress(config.getSmtpFrom()));
            msg.setRecipients(jakarta.mail.Message.RecipientType.TO,
                jakarta.mail.internet.InternetAddress.parse(email));
            msg.setSubject("LocalCloud verification code");
            msg.setText("Your LocalCloud verification code is: " + otp +
                "\n\nThis code expires in " + config.getOtpExpiryMinutes() + " minutes.");
            jakarta.mail.Transport.send(msg);
            logger.info("OTP email sent to {}", email);
        } catch (Exception e) {
            logger.error("Failed to send OTP email to {}: {}", email, e.getMessage());
            throw new RuntimeException("Email send failed", e);
        }
    }
}
