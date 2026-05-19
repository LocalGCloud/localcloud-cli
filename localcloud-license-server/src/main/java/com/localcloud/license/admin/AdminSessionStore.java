package com.localcloud.license.admin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AdminSessionStore {

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private static final long SESSION_DURATION_MS = 8 * 3600 * 1000L;
    private int validateCount = 0;

    public String createSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "adm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, System.currentTimeMillis() + SESSION_DURATION_MS);
        return token;
    }

    public boolean validateSession(String token) {
        if (token == null || !token.startsWith("adm_")) return false;
        if (++validateCount % 100 == 0) {
            cleanupExpired();
        }
        Long expiry = sessions.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void removeSession(String token) {
        sessions.remove(token);
    }

    void cleanupExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now > e.getValue());
    }
}
