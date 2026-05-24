package com.localcloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility client for supervisord's XML-RPC API.
 * Communicates with supervisord at http://localhost:9001/RPC2
 * to start, stop, and query process status.
 */
public class SupervisorClient {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorClient.class);
    private static final String SUPERVISOR_URL = "http://localhost:9001/RPC2";
    private static final int TIMEOUT_MS = 5000;

    /**
     * Start a supervised process by name.
     *
     * @param name the process name as configured in supervisord
     * @return true if the process was started (or was already running), false on failure
     */
    public boolean startProcess(String name) {
        try {
            String response = callXmlRpc("supervisor.startProcess", "<param><value><string>" + escapeXml(name) + "</string></value></param>");
            logger.info("Supervisor startProcess({}): success", name);
            return response != null && response.contains("<boolean>1</boolean>");
        } catch (Exception e) {
            logger.warn("Failed to start supervisor process '{}': {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Stop a supervised process by name.
     *
     * @param name the process name as configured in supervisord
     * @return true if the process was stopped (or was already stopped), false on failure
     */
    public boolean stopProcess(String name) {
        try {
            String response = callXmlRpc("supervisor.stopProcess", "<param><value><string>" + escapeXml(name) + "</string></value></param>");
            logger.info("Supervisor stopProcess({}): success", name);
            return response != null && response.contains("<boolean>1</boolean>");
        } catch (Exception e) {
            logger.warn("Failed to stop supervisor process '{}': {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Get the status of a supervised process.
     *
     * @param name the process name as configured in supervisord
     * @return a map with keys "name", "statename", "pid", "description"; or a fallback map on failure
     */
    public Map<String, String> getProcessStatus(String name) {
        Map<String, String> fallback = new LinkedHashMap<>();
        fallback.put("name", name);
        fallback.put("statename", "UNKNOWN");
        fallback.put("pid", "0");
        fallback.put("description", "Unable to reach supervisord");

        try {
            String response = callXmlRpc("supervisor.getProcessInfo", "<param><value><string>" + escapeXml(name) + "</string></value></param>");
            if (response == null) {
                return fallback;
            }

            Map<String, String> status = new LinkedHashMap<>();
            status.put("name", extractXmlValue(response, "name", name));
            status.put("statename", extractXmlValue(response, "statename", "UNKNOWN"));
            status.put("pid", extractXmlValue(response, "pid", "0"));
            status.put("description", extractXmlValue(response, "description", ""));
            return status;
        } catch (Exception e) {
            logger.warn("Failed to get supervisor process status for '{}': {}", name, e.getMessage());
            return fallback;
        }
    }

    /**
     * Get info for all supervised processes in a single XML-RPC call.
     *
     * @return map of program name -> {name, pid, statename, description}
     */
    public Map<String, Map<String, String>> getAllProcesses() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try {
            String response = callXmlRpc("supervisor.getAllProcessInfo", "");
            if (response == null) return result;

            int structStart = 0;
            while ((structStart = response.indexOf("<struct>", structStart)) >= 0) {
                int structEnd = response.indexOf("</struct>", structStart);
                if (structEnd < 0) break;
                String structXml = response.substring(structStart, structEnd + "</struct>".length());

                String name = extractXmlValue(structXml, "name", "");
                String pid = extractXmlValue(structXml, "pid", "0");
                String statename = extractXmlValue(structXml, "statename", "UNKNOWN");
                String description = extractXmlValue(structXml, "description", "");

                if (!name.isEmpty()) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("name", name);
                    info.put("pid", pid);
                    info.put("statename", statename);
                    info.put("description", description);
                    result.put(name, info);
                }

                structStart = structEnd + "</struct>".length();
            }
        } catch (Exception e) {
            logger.warn("Failed to get all supervisor processes: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Read RSS (resident memory) of a process in megabytes from /proc/[pid]/statm.
     * Returns 0 if the PID is 0 or /proc entry cannot be read.
     */
    public static long getProcessMemoryMb(long pid) {
        if (pid <= 0) return 0;
        try {
            String content = Files.readString(Path.of("/proc/" + pid + "/statm"));
            // statm format: size resident shared text lib data dt
            // resident is the second field, in pages (typically 4096 bytes)
            String[] parts = content.trim().split("\\s+");
            if (parts.length >= 2) {
                long pages = Long.parseLong(parts[1]);
                return (pages * 4096) / (1024 * 1024);
            }
        } catch (Exception e) {
            // Process may have exited, or /proc not available
        }
        return 0;
    }

    /**
     * Make an XML-RPC call to supervisord.
     */
    private String callXmlRpc(String methodName, String params) throws IOException {
        String xmlBody = "<?xml version=\"1.0\"?>"
                + "<methodCall>"
                + "<methodName>" + methodName + "</methodName>"
                + "<params>" + params + "</params>"
                + "</methodCall>";

        HttpURLConnection conn = (HttpURLConnection) URI.create(SUPERVISOR_URL).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(xmlBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (is == null) {
                return null;
            }

            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (statusCode >= 200 && statusCode < 300) {
                return response;
            } else {
                logger.warn("Supervisor XML-RPC {} returned HTTP {}: {}", methodName, statusCode, response);
                return null;
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Extract a string value from an XML-RPC struct member.
     * This is a simple parser that looks for member/name/value patterns.
     */
    private static String extractXmlValue(String xml, String memberName, String defaultValue) {
        // Look for <member><name>memberName</name><value>...value...</value></member>
        String nameTag = "<name>" + memberName + "</name>";
        int nameIdx = xml.indexOf(nameTag);
        if (nameIdx < 0) {
            return defaultValue;
        }
        int valueStart = xml.indexOf("<value>", nameIdx);
        if (valueStart < 0) {
            return defaultValue;
        }
        valueStart += "<value>".length();

        // The value might be wrapped in a type tag like <string>, <int>, etc.
        int valueEnd = xml.indexOf("</value>", valueStart);
        if (valueEnd < 0) {
            return defaultValue;
        }
        String rawValue = xml.substring(valueStart, valueEnd).trim();

        // Strip type tags if present (e.g. <string>...</string>, <int>...</int>)
        if (rawValue.startsWith("<")) {
            int innerStart = rawValue.indexOf('>') + 1;
            int innerEnd = rawValue.lastIndexOf('<');
            if (innerStart > 0 && innerEnd > innerStart) {
                return rawValue.substring(innerStart, innerEnd);
            }
        }
        return rawValue.isEmpty() ? defaultValue : rawValue;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
