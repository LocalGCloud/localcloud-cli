package com.localcloud.license.keys;

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
 * CLI tool for generating keys and keypairs for the license server.
 * Run from the license-server JAR (not the client JAR).
 *
 * Usage:
 *   java -cp localcloud-license-server.jar com.localcloud.license.keys.AdminCliKeyGen rsa
 *   java -cp ... AdminCliKeyGen keypair
 *   java -cp ... AdminCliKeyGen offline --private-key <b64> --email <email> [--tier pro] [--days 365]
 *   java -cp ... AdminCliKeyGen online
 *
 * Key types:
 *   rsa      — RSA-2048 keypair for LOCALCLOUD_LICENSE_ONLINE_PRIVATE_KEY (JWT signing)
 *   keypair  — Ed25519 keypair for LOCALCLOUD_LICENSE_OFFLINE_PRIVATE_KEY (offline signing)
 */
public final class AdminCliKeyGen {

    private static final ObjectMapper mapper = new ObjectMapper();

    private AdminCliKeyGen() {}

    public static String generateRsaKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        String priv = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPublic().getEncoded());
        return "RSA_PRIVATE_KEY=" + priv + "\nPUBLIC_KEY=" + pub +
               "\n\n# Set on license server (persistent JWT signing):" +
                "\nexport LOCALCLOUD_LICENSE_ONLINE_PRIVATE_KEY=" + priv +
               "\n\n# Set on clients (if not fetching from /license/public-key):" +
               "\nexport LOCALCLOUD_LICENSE_PUBLIC_KEY=" + pub +
               "\n\nKeep PRIVATE_KEY secret — used to sign JWT tokens for online keys (lc_on_).";
    }

    public static String generateKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        String priv = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPublic().getEncoded());
        // Kid = first 8 chars AFTER the Ed25519 DER prefix (16 base64 chars)
        String kid = pub.length() >= 24 ? pub.substring(16, 24) : pub.substring(0, Math.min(8, pub.length()));
        return "OFFLINE_PRIVATE_KEY=" + priv + "\nPUBLIC_KEY=" + pub + "\nKID=" + kid + "\n\n# Set on license server (Ed25519 offline key signing):\nexport LOCALCLOUD_LICENSE_OFFLINE_PRIVATE_KEY=" + priv + "\nexport LOCALCLOUD_LICENSE_OFFLINE_PUBLIC_KEY=" + pub + "\n\n# Set on clients:\nexport LOCALCLOUD_LICENSE_PUBLIC_KEY=" + pub + "\n\nKeep OFFLINE_PRIVATE_KEY secret — used to sign offline license keys (lc_of_).";
    }

    public static String generateOfflineKey(String privateKeyB64, String email, String tier,
                                             String deviceId, int days) throws Exception {
        byte[] pkBytes = Base64.getUrlDecoder().decode(privateKeyB64);
        KeyFactory kf = KeyFactory.getInstance("EdDSA");
        PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(pkBytes));
        // kid is not computed for CLI-generated keys (requires public key derivation).
        // Use the admin panel (AdminHandler) which has the public key for proper kid support.

        Instant now = Instant.now();
        long nowSec = now.getEpochSecond();
        long expires = now.plus(days, ChronoUnit.DAYS).getEpochSecond();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("email", email);
        claims.put("tier", tier);
        claims.put("issued", nowSec);
        claims.put("expires", expires);
        claims.put("offline", true);
        // Note: kid is not added by the CLI — use the admin panel for kid-enabled keys
        if (deviceId != null && !deviceId.isBlank()) {
            claims.put("device_id", deviceId);
        }

        byte[] payloadBytes = mapper.writeValueAsBytes(claims);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(pk);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01;
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lc_of_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    public static String generateOnlineKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return "lc_on_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Admin CLI Key Generator — runs from the license-server JAR");
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  rsa                              Generate RSA-2048 keypair for JWT signing (LOCALCLOUD_LICENSE_ONLINE_PRIVATE_KEY)");
            System.out.println("  keypair                          Generate Ed25519 keypair for offline signing (LOCALCLOUD_LICENSE_OFFLINE_PRIVATE_KEY)");
            System.out.println("  online                           Generate random online key");
            System.out.println("  offline --private-key <b64>      Generate signed offline key");
            System.out.println("          --email <email>");
            System.out.println("          [--tier pro]");
            System.out.println("          [--device-id <fingerprint>]");
            System.out.println("          [--days 365]");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  # RSA key for JWT signing (set as LOCALCLOUD_LICENSE_ONLINE_PRIVATE_KEY)");
            System.out.println("  java -cp build/libs/localcloud-license-server-*-all.jar \\");
            System.out.println("    com.localcloud.license.keys.AdminCliKeyGen rsa");
            System.out.println();
            System.out.println("  # Ed25519 key for offline signing (set as LOCALCLOUD_LICENSE_OFFLINE_PRIVATE_KEY)");
            System.out.println("  java -cp build/libs/localcloud-license-server-*-all.jar \\");
            System.out.println("    com.localcloud.license.keys.AdminCliKeyGen keypair");
            return;
        }

        switch (args[0]) {
            case "rsa" -> System.out.println(generateRsaKeypair());
            case "keypair" -> System.out.println(generateKeypair());
            case "online" -> System.out.println(generateOnlineKey());
            case "offline" -> {
                String privateKeyB64 = getArg(args, "--private-key");
                String email = getArg(args, "--email");
                String tier = getArgOr(args, "--tier", "pro");
                String deviceId = getArgOr(args, "--device-id", null);
                int days = Integer.parseInt(getArgOr(args, "--days", "365"));
                System.out.println(generateOfflineKey(privateKeyB64, email, tier, deviceId, days));
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