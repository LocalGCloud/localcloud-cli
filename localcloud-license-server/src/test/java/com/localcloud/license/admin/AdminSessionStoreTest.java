package com.localcloud.license.admin;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminSessionStoreTest {

    private AdminSessionStore store;

    @BeforeEach
    void setUp() {
        store = new AdminSessionStore();
    }

    @Test
    void createSessionReturnsValidToken() {
        String token = store.createSession();
        assertNotNull(token);
        assertTrue(token.startsWith("adm_"));
        assertEquals(47, token.length()); // "adm_" (4) + 32 bytes base64url (43) = 47
    }

    @Test
    void createdSessionIsValid() {
        String token = store.createSession();
        assertTrue(store.validateSession(token));
    }

    @Test
    void nullTokenIsRejected() {
        assertFalse(store.validateSession(null));
    }

    @Test
    void wrongPrefixTokenIsRejected() {
        assertFalse(store.validateSession("xxx_faketoken"));
    }

    @Test
    void unknownTokenIsRejected() {
        assertFalse(store.validateSession("adm_unknownToken12345"));
    }

    @Test
    void removedSessionIsInvalid() {
        String token = store.createSession();
        assertTrue(store.validateSession(token));
        store.removeSession(token);
        assertFalse(store.validateSession(token));
    }

    @Test
    void multipleSessionsAreIndependent() {
        String t1 = store.createSession();
        String t2 = store.createSession();
        assertTrue(store.validateSession(t1));
        assertTrue(store.validateSession(t2));
        store.removeSession(t1);
        assertFalse(store.validateSession(t1));
        assertTrue(store.validateSession(t2));
    }
}
