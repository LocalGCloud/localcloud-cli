package com.localcloud.licensing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * Minimal preflight license gate — runs BEFORE supervisord starts external emulators.
 *
 * Called from license-gate.sh inside docker-entrypoint.sh. Exits 0 on success,
 * exits 1 on failure. On success, writes the tier string to the --out file.
 *
 * Usage:
 *   java -cp localcloud.jar com.localcloud.licensing.LicenseGateMain \
 *       --key <api_key> \
 *       --server <url_or_none> \
 *       --device-id <id> \
 *       --out <filepath>
 *
 * All args are optional; missing --key and --server=none triggers bypass mode.
 */
public class LicenseGateMain {

    public static void main(String[] args) {
        String apiKey = null;
        String licenseServer = "none";
        String deviceIdOverride = null;
        String outFile = "/tmp/localcloud-tier";

        // Parse CLI args
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--key"       -> apiKey = args[++i];
                case "--server"    -> licenseServer = args[++i];
                case "--device-id" -> deviceIdOverride = args[++i];
                case "--out"       -> outFile = args[++i];
                default            -> { /* ignore unknown args */ }
            }
        }

        // Blank/empty strings treated as absent
        if (apiKey != null && apiKey.isBlank()) apiKey = null;
        if (deviceIdOverride != null && deviceIdOverride.isBlank()) deviceIdOverride = null;

        // Determine data dir — use /var/lib/localcloud if writable, else /tmp/localcloud-gate
        Path dataDir = Path.of("/var/lib/localcloud");
        if (!Files.isWritable(dataDir)) {
            dataDir = Path.of("/tmp/localcloud-gate");
            try {
                Files.createDirectories(dataDir);
            } catch (Exception e) {
                // Fallback: cache will be disabled, validation still proceeds
                System.err.println("Warning: cannot create gate data dir: " + e.getMessage());
            }
        }

        // Load offline public key the same way LocalCloudApplication does:
        // read LOCALCLOUD_LICENSE_PUBLIC_KEY env var.
        PublicKey licensePublicKey = null;
        try {
            String pubKeyEnv = System.getenv("LOCALCLOUD_LICENSE_PUBLIC_KEY");
            if (pubKeyEnv != null && !pubKeyEnv.isBlank()) {
                licensePublicKey = KeyGenerator.decodePublicKey(pubKeyEnv);
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to load license public key: " + e.getMessage());
        }

        LicenseManager licenseManager = new LicenseManager(
                apiKey,
                licenseServer,
                dataDir,
                licensePublicKey);

        LicenseResult result = licenseManager.validate();

        if (!result.isValid()) {
            System.err.println("ERROR: License validation failed: " + result.errorMessage());
            System.err.println("       Set LOCALCLOUD_API_KEY or get a key at https://localcloud.dev");
            System.exit(1);
        }

        // Write tier to output file so entrypoint and server can read it
        String tierName = result.tier() != null ? result.tier().name().toLowerCase() : "development";
        try {
            Files.writeString(Path.of(outFile), tierName);
        } catch (Exception e) {
            // Non-fatal: entrypoint still reads it, but if it fails we warn and continue
            System.err.println("Warning: failed to write tier file " + outFile + ": " + e.getMessage());
        }

        System.out.println("License: valid — tier=" + tierName
                + (result.email() != null ? ", email=" + result.email() : ""));
        System.exit(0);
    }
}
