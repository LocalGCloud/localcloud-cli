package com.localcloud.license.auth;

import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class AuthRepositoryTest {

    private DataSource ds;
    private AuthRepository repo;
    private OtpService otpService;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:auth_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.repo = new AuthRepository(ds);
        this.otpService = new OtpService(ds, 15);
    }

    @Test
    void registerCreatesUser() throws Exception {
        repo.createUser("test@example.com");
        assertTrue(repo.userExists("test@example.com"));
    }

    @Test
    void duplicateEmailIsIdempotent() throws Exception {
        repo.createUser("dup@example.com");
        assertDoesNotThrow(() -> repo.createUser("dup@example.com"));
        assertTrue(repo.userExists("dup@example.com"));
    }

    @Test
    void otpIsGeneratedAndValidated() throws Exception {
        repo.createUser("otp@example.com");
        String code = otpService.generateOtp("otp@example.com");
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d{6}"));
        assertTrue(otpService.verifyOtp("otp@example.com", code));
    }

    @Test
    void wrongOtpIsRejected() throws Exception {
        repo.createUser("wrong@example.com");
        otpService.generateOtp("wrong@example.com");
        assertFalse(otpService.verifyOtp("wrong@example.com", "000000"));
    }

    @Test
    void otpIsConsumedAfterUse() throws Exception {
        repo.createUser("consume@example.com");
        String code = otpService.generateOtp("consume@example.com");
        assertTrue(otpService.verifyOtp("consume@example.com", code));
        assertFalse(otpService.verifyOtp("consume@example.com", code));
    }

    @Test
    void emailVerificationUpdatesUser() throws Exception {
        repo.createUser("verify@example.com");
        assertFalse(repo.isEmailVerified("verify@example.com"));
        String code = otpService.generateOtp("verify@example.com");
        otpService.verifyOtp("verify@example.com", code);
        repo.markEmailVerified("verify@example.com");
        assertTrue(repo.isEmailVerified("verify@example.com"));
    }
}
