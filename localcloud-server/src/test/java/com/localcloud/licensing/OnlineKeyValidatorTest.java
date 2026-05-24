package com.localcloud.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OnlineKeyValidatorTest {

    // --- Existing bypass / error tests ---

    @Test
    void bypassModeAcceptsAnyOnlineKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void rejectsNonOnlinePrefix() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lck_someofflinekey", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("prefix"));
    }

    @Test
    void rejectsNullKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate(null, "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void rejectsEmptyKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_", "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void unreachableServerReturnsError() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19999");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("unreachable") || result.errorMessage().contains("connect"));
    }

    @Test
    void httpUrlToNonLocalhostLogsWarningAndFails() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://api.example.com");
        LicenseResult result = validator.validate("lco_somekey", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("HTTPS") || result.errorMessage().contains("insecure"),
                "Should reject non-HTTPS non-localhost URL, got: " + result.errorMessage());
    }

    @Test
    void httpLocalhostIsAllowedForDevelopment() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19998");
        LicenseResult result = validator.validate("lco_somekey", "device-id");
        assertFalse(result.isValid());
        assertFalse(result.errorMessage().contains("HTTPS"),
                "localhost http:// should not trigger HTTPS error, got: " + result.errorMessage());
    }

    // --- JWT signature verification tests using an embedded HTTP server ---

    private HttpServer mockServer;
    private KeyPair signingKeyPair;
    private String serverUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startMockServer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        signingKeyPair = gen.generateKeyPair();

        mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = mockServer.getAddress().getPort();
        serverUrl = "http://localhost:" + port;
    }

    @AfterEach
    void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    private String buildJwt(String tier, String email, String deviceId, long expiresEpoch) {
        return Jwts.builder()
            .issuer("localcloud-license")
            .subject(email != null ? email : "unknown")
            .claim("tier", tier)
            .claim("device_id", deviceId != null ? deviceId : "")
            .expiration(new Date(expiresEpoch * 1000L))
            .signWith(signingKeyPair.getPrivate())
            .compact();
    }

    private void registerPublicKeyEndpoint() throws Exception {
        String pubKeyB64 = Base64.getEncoder().encodeToString(signingKeyPair.getPublic().getEncoded());
        byte[] responseBytes = mapper.writeValueAsBytes(
            Map.of("algorithm", "RS256", "key", pubKeyB64));

        mockServer.createContext("/license/public-key", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.getResponseBody().close();
        });
    }

    private void registerValidateEndpoint(String jwt) throws Exception {
        byte[] responseBytes = mapper.writeValueAsBytes(Map.of("token", jwt));
        mockServer.createContext("/license/validate", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.getResponseBody().close();
        });
    }

    private void registerValidateEndpoint(int statusCode, Map<String, String> body) throws Exception {
        byte[] responseBytes = mapper.writeValueAsBytes(body);
        mockServer.createContext("/license/validate", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.getResponseBody().close();
        });
    }

    @Test
    void acceptsValidJwtFromServer() throws Exception {
        long expires = System.currentTimeMillis() / 1000L + 3600;
        String jwt = buildJwt("pro", "user@example.com", "device-abc", expires);

        registerPublicKeyEndpoint();
        registerValidateEndpoint(jwt);
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_somekey", "device-abc");

        assertTrue(result.isValid(), "Expected valid, got: " + result.errorMessage());
        assertEquals(LicenseTier.PRO, result.tier());
        assertEquals("user@example.com", result.email());
    }

    @Test
    void rejectsTamperedJwt() throws Exception {
        long expires = System.currentTimeMillis() / 1000L + 3600;
        String realJwt = buildJwt("pro", "user@example.com", "device-abc", expires);

        // Tamper: flip a leading signature character so the decoded signature bytes change.
        String[] parts = realJwt.split("\\.");
        String sig = parts[2];
        char first = sig.charAt(0);
        char tampered = first == 'A' ? 'B' : 'A';
        String tamperedJwt = parts[0] + "." + parts[1] + "." + tampered + sig.substring(1);

        registerPublicKeyEndpoint();
        registerValidateEndpoint(tamperedJwt);
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_somekey", "device-abc");

        assertFalse(result.isValid(), "Tampered JWT should be rejected");
        assertTrue(result.errorMessage().contains("signature") || result.errorMessage().contains("verification"),
                "Error should mention signature, got: " + result.errorMessage());
    }

    @Test
    void rejectsJwtSignedWithDifferentKey() throws Exception {
        // Sign with a different key pair than the one exposed at /public-key
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair otherKeyPair = gen.generateKeyPair();

        long expires = System.currentTimeMillis() / 1000L + 3600;
        String jwt = Jwts.builder()
            .issuer("localcloud-license")
            .subject("attacker@evil.com")
            .claim("tier", "enterprise")
            .claim("device_id", "device-x")
            .expiration(new Date(expires * 1000L))
            .signWith(otherKeyPair.getPrivate())   // wrong key
            .compact();

        registerPublicKeyEndpoint();  // exposes the correct (different) public key
        registerValidateEndpoint(jwt);
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_somekey", "device-x");

        assertFalse(result.isValid(), "JWT signed with wrong key should be rejected");
    }

    @Test
    void rejectsExpiredJwt() throws Exception {
        long pastExpires = System.currentTimeMillis() / 1000L - 60;
        String jwt = buildJwt("pro", "user@example.com", "device-abc", pastExpires);

        registerPublicKeyEndpoint();
        registerValidateEndpoint(jwt);
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_somekey", "device-abc");

        assertFalse(result.isValid(), "Expired JWT should be rejected");
    }

    @Test
    void handles401FromServerAsInvalid() throws Exception {
        registerPublicKeyEndpoint();
        registerValidateEndpoint(401, Map.of("error", "Invalid or revoked key"));
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_badkey", "device-abc");

        assertFalse(result.isValid());
    }

    @Test
    void communityTierIsParsedCorrectly() throws Exception {
        long expires = System.currentTimeMillis() / 1000L + 3600;
        String jwt = buildJwt("community", "free@example.com", "device-1", expires);

        registerPublicKeyEndpoint();
        registerValidateEndpoint(jwt);
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);
        LicenseResult result = validator.validate("lco_somekey", "device-1");

        assertTrue(result.isValid());
        assertEquals(LicenseTier.COMMUNITY, result.tier());
    }

    @Test
    void publicKeyIsCachedAfterFirstFetch() throws Exception {
        long expires = System.currentTimeMillis() / 1000L + 3600;
        String jwt = buildJwt("pro", "user@example.com", "device-abc", expires);

        int[] publicKeyCallCount = {0};
        String pubKeyB64 = Base64.getEncoder().encodeToString(signingKeyPair.getPublic().getEncoded());
        byte[] pkResponse = mapper.writeValueAsBytes(Map.of("algorithm", "RS256", "key", pubKeyB64));

        mockServer.createContext("/license/public-key", exchange -> {
            publicKeyCallCount[0]++;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, pkResponse.length);
            exchange.getResponseBody().write(pkResponse);
            exchange.getResponseBody().close();
        });

        byte[] valResponse = mapper.writeValueAsBytes(Map.of("token", jwt));
        mockServer.createContext("/license/validate", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, valResponse.length);
            exchange.getResponseBody().write(valResponse);
            exchange.getResponseBody().close();
        });
        mockServer.start();

        OnlineKeyValidator validator = new OnlineKeyValidator(serverUrl);

        // Call twice — public key should only be fetched once
        validator.validate("lco_somekey", "device-abc");
        validator.validate("lco_somekey", "device-abc");

        assertEquals(1, publicKeyCallCount[0], "Public key should be fetched only once and then cached");
    }
}
