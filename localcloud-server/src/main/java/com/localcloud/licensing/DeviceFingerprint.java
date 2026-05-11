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
        String mac = readMacAddress();
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
            // Cross-platform fallback: OS MXBean
            try {
                var bean = (com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                return bean.getTotalMemorySize() / (1024 * 1024);
            } catch (Exception ex) {
                return 0L;
            }
        }
    }

    /**
     * Read primary MAC address. Tries Linux /sys first, then Java NetworkInterface (cross-platform).
     * Package-private for testing.
     */
    static String readMacAddress() {
        // Linux/Docker: try /sys/class/net first
        try {
            Path netDir = Path.of("/sys/class/net");
            if (Files.isDirectory(netDir)) {
                try (Stream<Path> dirs = Files.list(netDir)) {
                    String linuxMac = dirs
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
                            .orElse(null);
                    if (linuxMac != null) return linuxMac;
                }
            }
        } catch (IOException ignored) {}

        // Cross-platform fallback: java.net.NetworkInterface
        try {
            return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                    .stream()
                    .filter(ni -> {
                        try {
                            return !ni.isLoopback() && ni.isUp() && ni.getHardwareAddress() != null;
                        } catch (java.net.SocketException e) {
                            return false;
                        }
                    })
                    .map(ni -> {
                        try {
                            byte[] mac = ni.getHardwareAddress();
                            if (mac == null) return null;
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < mac.length; i++) {
                                if (i > 0) sb.append(':');
                                sb.append(String.format("%02x", mac[i]));
                            }
                            return sb.toString();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse("no-mac");
        } catch (java.net.SocketException e) {
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
