package com.localcloud.emulators.workflows.stdlib;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class HashFunctions {
    private static final Map<String, String> ALGORITHM_MAP = Map.of(
        "SHA-256", "SHA-256", "SHA-384", "SHA-384", "SHA-512", "SHA-512",
        "MD5", "MD5", "SHA-1", "SHA-1"
    );
    private static final Map<String, String> HMAC_ALGORITHM_MAP = Map.of(
        "SHA-256", "HmacSHA256", "SHA-384", "HmacSHA384", "SHA-512", "HmacSHA512",
        "MD5", "HmacMD5", "SHA-1", "HmacSHA1"
    );

    public static void register(StdlibRegistry registry) {
        registry.register("hash.compute_checksum", HashFunctions::computeChecksum);
        registry.register("hash.compute_hmac", HashFunctions::computeHmac);

        // crypto.* aliases
        registry.register("crypto.compute_checksum", HashFunctions::computeChecksum);
        registry.register("crypto.compute_hmac", HashFunctions::computeHmac);
    }

    private static Object computeChecksum(List<Object> args) {
        if (args.size() < 2) throw new RuntimeException("hash.compute_checksum requires (data, algorithm)");
        String data = String.valueOf(args.get(0));
        String algorithm = String.valueOf(args.get(1));
        String javaAlg = ALGORITHM_MAP.get(algorithm);
        if (javaAlg == null) throw new RuntimeException("Unsupported hash algorithm: " + algorithm);
        try {
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("hash.compute_checksum failed: " + e.getMessage(), e);
        }
    }

    private static Object computeHmac(List<Object> args) {
        if (args.size() < 3) throw new RuntimeException("hash.compute_hmac requires (data, key, algorithm)");
        String data = String.valueOf(args.get(0));
        String key = String.valueOf(args.get(1));
        String algorithm = String.valueOf(args.get(2));
        String javaAlg = HMAC_ALGORITHM_MAP.get(algorithm);
        if (javaAlg == null) throw new RuntimeException("Unsupported HMAC algorithm: " + algorithm);
        try {
            Mac mac = Mac.getInstance(javaAlg);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), javaAlg));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("hash.compute_hmac failed: " + e.getMessage(), e);
        }
    }
}
