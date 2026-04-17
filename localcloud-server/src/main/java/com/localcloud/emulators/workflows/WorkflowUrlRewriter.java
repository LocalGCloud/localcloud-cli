package com.localcloud.emulators.workflows;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans workflow YAML for remote proxy URL patterns and replaces them
 * with ${SERVICE_NAME_URL}/path environment variable patterns.
 */
public class WorkflowUrlRewriter {

    // Matches: http://HOST/proxy/ENV/SERVICE or http://HOST/proxy/ENV/SERVICE/PATH
    private static final Pattern PROXY_URL_PATTERN = Pattern.compile(
        "http://([^/]+)/proxy/([^/]+)/([^/\\s\"']+)(/[^\\s\"']*)?");

    /**
     * Result of URL detection in YAML.
     */
    public static class UrlMatch {
        public final String fullUrl;
        public final String envName;
        public final String serviceName;
        public final String pathSuffix;
        public final String proxyBase;

        public UrlMatch(String fullUrl, String envName, String serviceName, String pathSuffix, String proxyBase) {
            this.fullUrl = fullUrl;
            this.envName = envName;
            this.serviceName = serviceName;
            this.pathSuffix = pathSuffix;
            this.proxyBase = proxyBase;
        }
    }

    /**
     * Detect all remote proxy URLs in the YAML.
     */
    public static List<UrlMatch> detect(String yaml) {
        List<UrlMatch> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = PROXY_URL_PATTERN.matcher(yaml);
        while (m.find()) {
            String fullUrl = m.group(0);
            String host = m.group(1);
            String envName = m.group(2);
            String serviceName = m.group(3);
            String pathSuffix = m.group(4) != null ? m.group(4) : "";
            String proxyBase = "http://" + host + "/proxy/" + envName + "/" + serviceName;

            // De-duplicate by service name (keep first)
            if (!seen.contains(serviceName)) {
                matches.add(new UrlMatch(fullUrl, envName, serviceName, pathSuffix, proxyBase));
                seen.add(serviceName);
            }
        }
        return matches;
    }

    /**
     * Derive the env var name from a service name.
     * e.g., "payment-service" -> "PAYMENT_SERVICE_URL"
     */
    public static String toVarName(String serviceName) {
        return serviceName.toUpperCase().replace('-', '_') + "_URL";
    }

    /**
     * Rewrite all proxy URLs in the YAML with ${VAR_NAME} patterns.
     * Returns the rewritten YAML.
     */
    public static String rewrite(String yaml) {
        // Collect all unique services first, grouped by (host, env, service) -> varName
        Matcher m = PROXY_URL_PATTERN.matcher(yaml);
        // Replace all occurrences
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String host = m.group(1);
            String envName = m.group(2);
            String serviceName = m.group(3);
            String pathSuffix = m.group(4) != null ? m.group(4) : "";
            String varName = toVarName(serviceName);
            String replacement = "${" + varName + "}" + pathSuffix;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Generate env var entries for all three presets for each discovered service.
     */
    public static List<Map<String, String>> generateEnvVarEntries(List<UrlMatch> matches) {
        List<Map<String, String>> entries = new ArrayList<>();
        Set<String> seenServices = new HashSet<>();

        for (UrlMatch match : matches) {
            if (seenServices.contains(match.serviceName)) continue;
            seenServices.add(match.serviceName);

            String varName = toVarName(match.serviceName);

            // Remote preset: auto-populated from proxy base
            entries.add(Map.of("varName", varName, "varValue", match.proxyBase, "preset", "remote"));
            // Local preset: empty (user fills in)
            entries.add(Map.of("varName", varName, "varValue", "", "preset", "local"));
            // Production preset: empty (user fills in)
            entries.add(Map.of("varName", varName, "varValue", "", "preset", "production"));
        }
        return entries;
    }
}
