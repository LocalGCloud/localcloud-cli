package com.localcloud.emulators.workflows.stdlib;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextFunctions {

    private static Pattern safeCompile(String regex) {
        if (regex.length() > 200) throw new RuntimeException("Regex too long (max 200 chars)");
        try {
            return Pattern.compile(regex);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new RuntimeException("Invalid regex: " + e.getMessage());
        }
    }

    public static void register(StdlibRegistry registry) {
        registry.register("text.find_all", args -> {
            if (args.size() < 2) throw new RuntimeException("text.find_all requires (string, regex)");
            String input = String.valueOf(args.get(0));
            Pattern p = safeCompile(String.valueOf(args.get(1)));
            Matcher m = p.matcher(input);
            List<String> results = new ArrayList<>();
            while (m.find()) results.add(m.group());
            return results;
        });
        registry.register("text.find_all_regex", registry.get("text.find_all"));

        registry.register("text.match_regex", args -> {
            if (args.size() < 2) throw new RuntimeException("text.match_regex requires (string, regex)");
            Pattern p = safeCompile(String.valueOf(args.get(1)));
            return p.matcher(String.valueOf(args.get(0))).matches();
        });

        registry.register("text.replace_all", args -> {
            if (args.size() < 3) throw new RuntimeException("text.replace_all requires (string, regex, replacement)");
            Pattern p = safeCompile(String.valueOf(args.get(1)));
            try {
                return p.matcher(String.valueOf(args.get(0))).replaceAll(String.valueOf(args.get(2)));
            } catch (Exception e) {
                throw new RuntimeException("text.replace_all failed: " + e.getMessage());
            }
        });
        registry.register("text.replace_all_regex", registry.get("text.replace_all"));

        registry.register("text.split", args -> {
            if (args.size() < 2) throw new RuntimeException("text.split requires (string, delimiter)");
            return Arrays.asList(String.valueOf(args.get(0)).split(String.valueOf(args.get(1))));
        });

        registry.register("text.substring", args -> {
            if (args.size() < 3) throw new RuntimeException("text.substring requires (string, start, end)");
            String s = String.valueOf(args.get(0));
            int start = ((Number) args.get(1)).intValue();
            int end = ((Number) args.get(2)).intValue();
            return s.substring(start, Math.min(end, s.length()));
        });

        registry.register("text.to_lower", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.to_lower requires a string");
            return String.valueOf(args.get(0)).toLowerCase();
        });

        registry.register("text.to_upper", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.to_upper requires a string");
            return String.valueOf(args.get(0)).toUpperCase();
        });

        registry.register("text.url_encode", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.url_encode requires a string");
            return URLEncoder.encode(String.valueOf(args.get(0)), StandardCharsets.UTF_8)
                    .replace("+", "%20");
        });

        registry.register("text.url_encode_plus", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.url_encode_plus requires a string");
            return URLEncoder.encode(String.valueOf(args.get(0)), StandardCharsets.UTF_8);
        });

        registry.register("text.url_decode", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.url_decode requires a string");
            return URLDecoder.decode(String.valueOf(args.get(0)).replace("+", "%2B"), StandardCharsets.UTF_8);
        });

        registry.register("text.url_decode_plus", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.url_decode_plus requires a string");
            return URLDecoder.decode(String.valueOf(args.get(0)), StandardCharsets.UTF_8);
        });

        registry.register("text.encode", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.encode requires a string");
            return String.valueOf(args.get(0)).getBytes(StandardCharsets.UTF_8);
        });

        registry.register("text.decode", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.decode requires bytes or a string");
            Object input = args.get(0);
            if (input instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
            return String.valueOf(input);
        });

        registry.register("text.replace_first", args -> {
            if (args.size() < 3) throw new RuntimeException("text.replace_first requires (string, regex, replacement)");
            Pattern p = safeCompile(String.valueOf(args.get(1)));
            try {
                return p.matcher(String.valueOf(args.get(0))).replaceFirst(String.valueOf(args.get(2)));
            } catch (Exception e) {
                throw new RuntimeException("text.replace_first failed: " + e.getMessage());
            }
        });

        registry.register("text.trim", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.trim requires a string");
            return String.valueOf(args.get(0)).trim();
        });

        registry.register("text.starts_with", args -> {
            if (args.size() < 2) throw new RuntimeException("text.starts_with requires (string, prefix)");
            return String.valueOf(args.get(0)).startsWith(String.valueOf(args.get(1)));
        });

        registry.register("text.ends_with", args -> {
            if (args.size() < 2) throw new RuntimeException("text.ends_with requires (string, suffix)");
            return String.valueOf(args.get(0)).endsWith(String.valueOf(args.get(1)));
        });

        registry.register("text.contains", args -> {
            if (args.size() < 2) throw new RuntimeException("text.contains requires (string, substring)");
            return String.valueOf(args.get(0)).contains(String.valueOf(args.get(1)));
        });

        registry.register("text.format", args -> {
            if (args.isEmpty()) throw new RuntimeException("text.format requires at least a format string");
            String format = String.valueOf(args.get(0));
            Object[] formatArgs = new Object[args.size() - 1];
            for (int i = 1; i < args.size(); i++) formatArgs[i - 1] = args.get(i);
            try {
                return String.format(format, formatArgs);
            } catch (java.util.IllegalFormatException e) {
                throw new RuntimeException("text.format failed: " + e.getMessage());
            }
        });
    }
}
