package com.localcloud.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI tool and utility for generating Ed25519 keypairs and license keys.
 *
 * Usage as CLI:
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator keypair
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator online
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator offline \
 *       --private-key <base64> --email user@co.com --tier pro --days 365
 */
public final class KeyGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();

    private KeyGenerator() {}

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        return kpg.generateKeyPair();
    }

    public static String generateOfflineKey(PrivateKey privateKey, String email, String tier,
                                             String deviceId, int days) throws Exception {
        long now = Instant.now().getEpochSecond();
        long expires = Instant.now().plus(days, ChronoUnit.DAYS).getEpochSecond();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("email", email);
        claims.put("tier", tier);
        claims.put("issued", now);
        claims.put("expires", expires);
        claims.put("offline", true);
        if (deviceId != null && !deviceId.isBlank()) {
            claims.put("device_id", deviceId);
        }

        byte[] payloadBytes = mapper.writeValueAsBytes(claims);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01;
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    public static String generateOnlineKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return "lco_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getUrlDecoder().decode(encoded);
        KeyFactory kf = KeyFactory.getInstance("EdDSA");
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage:");
            System.out.println("  keypair                          Generate Ed25519 keypair");
            System.out.println("  online                           Generate random online key");
            System.out.println("  offline --private-key <b64>      Generate signed offline key");
            System.out.println("         --email <email>");
            System.out.println("         --tier <pro|team|enterprise>");
            System.out.println("         [--device-id <fingerprint>]");
            System.out.println("         [--days <validity-days>]");
            return;
        }

        switch (args[0]) {
            case "keypair" -> {
                KeyPair kp = generateKeyPair();
                System.out.println("PRIVATE_KEY=" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(kp.getPrivate().getEncoded()));
                System.out.println("PUBLIC_KEY=" + encodePublicKey(kp.getPublic()));
                System.out.println("\nEmbed PUBLIC_KEY in LicenseManager.java");
                System.out.println("Keep PRIVATE_KEY secret on your license server");
            }
            case "online" -> System.out.println(generateOnlineKey());
            case "offline" -> {
                String privateKeyB64 = getArg(args, "--private-key");
                String email = getArg(args, "--email");
                String tier = getArgOr(args, "--tier", "pro");
                String deviceId = getArgOr(args, "--device-id", null);
                int days = Integer.parseInt(getArgOr(args, "--days", "365"));

                byte[] pkBytes = Base64.getUrlDecoder().decode(privateKeyB64);
                KeyFactory kf = KeyFactory.getInstance("EdDSA");
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(pkBytes));

                System.out.println(generateOfflineKey(pk, email, tier, deviceId, days));
            }
            default -> System.err.println("Unknown command: " + args[0]);
        }
    }

    private static String getArg(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        throw new IllegalArgumentException("Missing required argument: " + name);
    }

    private static String getArgOr(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return defaultValue;
    }
}
