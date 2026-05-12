package com.localcloud.license.auth;

import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryTest {

    private DataSource ds;
    private SessionRepository sessionRepo;
    private AuthRepository authRepo;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:sessions_" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.sessionRepo = new SessionRepository(ds);
        this.authRepo = new AuthRepository(ds);
    }

    @Test
    void createAndValidate_returnsUserId() throws Exception {
        UUID userId = authRepo.createUser("session_test@example.com");
        String token = sessionRepo.createSession(userId);
        assertNotNull(token);
        assertFalse(token.isBlank());
        UUID resolved = sessionRepo.validateSession(token);
        assertEquals(userId, resolved);
    }

    @Test
    void expireSession_thenValidate_returnsNull() throws Exception {
        UUID userId = authRepo.createUser("expire_test@example.com");
        String token = sessionRepo.createSession(userId);
        assertTrue(sessionRepo.expireSession(token));
        assertNull(sessionRepo.validateSession(token));
    }

    @Test
    void expireSession_nonExistentToken_returnsFalse() throws Exception {
        assertFalse(sessionRepo.expireSession(UUID.randomUUID().toString()));
    }

    @Test
    void invalidToken_returnsNull() throws Exception {
        UUID resolved = sessionRepo.validateSession(UUID.randomUUID().toString());
        assertNull(resolved);
    }

    @Test
    void nullToken_returnsNull() throws Exception {
        assertNull(sessionRepo.validateSession(null));
    }

    @Test
    void blankToken_returnsNull() throws Exception {
        assertNull(sessionRepo.validateSession("   "));
    }
}
