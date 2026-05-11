package com.localcloud.licensing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Computes a stable device fingerprint from hardware signals.
 * The fingerprint is a SHA-256 hex string derived from CPU, RAM, MAC address,
 * disk serial, and kernel version. Same physical machine always produces
 * the same fingerprint regardless of container rebuilds or volume deletion.
 */
public final class DeviceFingerprint {

    private DeviceFingerprint() {}

    /**
     * Compute device fingerprint from live system hardware signals.
     * Falls back gracefully if any signal is unavailable.
     */
    public static String compute() {
        String cpuModel = readCpuModel();
        int cores = Runtime.getRuntime().availableProcessors();
        long ramMb = readTotalRamMb();
        String mac = readPrimaryMac();
        String diskSerial = readDiskSerial();
        String kernel = System.getProperty("os.version", "unknown");
        return fromComponents(cpuModel, cores, ramMb, mac, diskSerial, kernel);
    }

    /**
     * Compute fingerprint from explicit components (for testing).
     */
    public static String fromComponents(String cpuModel, int cores, long ramMb,
                                         String mac, String diskSerial, String kernel) {
        String raw = cpuModel + ":" + cores + ":" + ramMb + ":" + mac + ":" + diskSerial + ":" + kernel;
        return sha256Hex(raw);
    }

    private static String readCpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unknown-cpu");
        } catch (IOException e) {
            return "unknown-cpu";
        }
    }

    private static long readTotalRamMb() {
        try {
            return Files.readAllLines(Path.of("/proc/meminfo")).stream()
                    .filter(line -> line.startsWith("MemTotal"))
                    .map(line -> {
                        String[] parts = line.split("\\s+");
                        return Long.parseLong(parts[1]) / 1024; // kB to MB
                    })
                    .findFirst()
                    .orElse(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String readPrimaryMac() {
        try {
            Path netDir = Path.of("/sys/class/net");
            if (!Files.isDirectory(netDir)) return "no-mac";
            try (Stream<Path> dirs = Files.list(netDir)) {
                return dirs
                        .filter(p -> !p.getFileName().toString().equals("lo"))
                        .map(p -> {
                            try {
                                return Files.readString(p.resolve("address")).trim();
                            } catch (IOException e) {
                                return "";
                            }
                        })
                        .filter(addr -> !addr.isBlank() && !addr.equals("00:00:00:00:00:00"))
                        .findFirst()
                        .orElse("no-mac");
            }
        } catch (IOException e) {
            return "no-mac";
        }
    }

    private static String readDiskSerial() {
        try {
            Path blockDir = Path.of("/sys/block");
            if (!Files.isDirectory(blockDir)) return "no-serial";
            try (Stream<Path> dirs = Files.list(blockDir)) {
                return dirs
                        .map(p -> {
                            try {
                                Path serial = p.resolve("serial");
                                if (Files.exists(serial)) {
                                    return Files.readString(serial).trim();
                                }
                                return "";
                            } catch (IOException e) {
                                return "";
                            }
                        })
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse("no-serial");
            }
        } catch (IOException e) {
            return "no-serial";
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
