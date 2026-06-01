package com.localcloud.emulators.common;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for registering Armeria regex routes to work around the annotation
 * parser's handling of ':' in path parameters.
 * <p>
 * Many GCP REST APIs use custom methods with colon syntax like
 * {@code /v1/projects/{p}/.../{resource}:encrypt}. Armeria's annotation parser
 * treats ':' as a regex delimiter inside path parameters, so annotated service
 * methods like {@code @Post("/{cryptoKey}:encrypt")} fail to match.
 * This helper generates the equivalent {@code Route.builder().path("regex:^...$")}
 * route with extracted path parameters.
 * </p>
 */
public final class RegexRouteHelper {

    /** Pattern to extract {paramName} from path templates. */
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)\\}");

    private RegexRouteHelper() {}

    /**
     * Register a regex route that expects a request body (POST/PUT/PATCH).
     *
     * @param sb       Armeria server builder
     * @param method   HTTP method
     * @param template path template like {@code /v1/projects/{project}/...:verb}
     * @param handler  handler receiving the aggregated request body
     */
    public static void registerVerbRoute(ServerBuilder sb, HttpMethod method,
                                          String template,
                                          BiFunction<ServiceRequestContext, AggregatedHttpRequest, HttpResponse> handler) {
        String regex = toRegex(template);
        sb.service(
            Route.builder().methods(method).path("regex:" + regex).build(),
            (ctx, req) -> handler.apply(ctx, req.aggregate().join()));
    }

    /**
     * Register a regex route that does not need a request body (GET/DELETE).
     *
     * @param sb       Armeria server builder
     * @param method   HTTP method
     * @param template path template
     * @param handler  handler receiving the request context
     */
    public static void registerVerbRoute(ServerBuilder sb, HttpMethod method,
                                          String template,
                                          Function<ServiceRequestContext, HttpResponse> handler) {
        String regex = toRegex(template);
        sb.service(
            Route.builder().methods(method).path("regex:" + regex).build(),
            (ctx, req) -> handler.apply(ctx));
    }

    /**
     * Convert a path template with {@code {param}} placeholders to an Armeria
     * regex path with {@code (?<param>...)} named capture groups.
     * <p>
     * Example: {@code /v1/projects/{project}/locations/{location}/jobs}
     * becomes {@code ^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs$}
     * </p>
     */
    static String toRegex(String template) {
        Matcher m = TEMPLATE_PARAM.matcher(template);
        StringBuilder sb = new StringBuilder("^");
        int lastEnd = 0;
        while (m.find()) {
            // Escape the literal text between params
            String literal = template.substring(lastEnd, m.start());
            sb.append(Pattern.quote(literal));
            String paramName = m.group(1);
            // If this param appears before a colon-verb, use [^:] instead of [^/]
            int colonIdx = template.indexOf(':', m.end());
            boolean beforeColon = colonIdx > m.end() && colonIdx < template.length();
            sb.append("(?<").append(paramName).append(">");
            sb.append(beforeColon ? "[^:]+" : "[^/]+");
            sb.append(")");
            lastEnd = m.end();
        }
        // Append remaining literal text (may include ':verb' suffix)
        String remaining = template.substring(lastEnd);
        // Escape character-by-character to handle colon verbs
        for (int i = 0; i < remaining.length(); i++) {
            char c = remaining.charAt(i);
            if (c == ':') sb.append(":");
            else if (c == '$') sb.append("\\$");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        sb.append("$");
        return sb.toString();
    }
}
