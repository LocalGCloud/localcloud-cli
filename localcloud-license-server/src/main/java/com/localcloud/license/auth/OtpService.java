package com.localcloud.license.auth;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class OtpService {

    private final DataSource dataSource;
    private final int expiryMinutes;
    private final SecureRandom random = new SecureRandom();

    public OtpService(DataSource dataSource, int expiryMinutes) {
        this.dataSource = dataSource;
        this.expiryMinutes = expiryMinutes;
    }

    public String generateOtp(String email) throws SQLException {
        String code = String.format("%06d", random.nextInt(1_000_000));
        Timestamp expires = Timestamp.from(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES));

        // Invalidate existing OTPs for this email
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE otp_codes SET used = TRUE WHERE email = ? AND used = FALSE")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.executeUpdate();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO otp_codes (email, code, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.setString(2, code);
            ps.setTimestamp(3, expires);
            ps.executeUpdate();
        }
        return code;
    }

    public boolean verifyOtp(String email, String code) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE otp_codes SET used = TRUE " +
                 "WHERE email = ? AND code = ? AND used = FALSE AND expires_at > NOW()")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.setString(2, code);
            return ps.executeUpdate() > 0;
        }
    }
}
