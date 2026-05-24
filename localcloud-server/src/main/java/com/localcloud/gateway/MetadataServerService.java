package com.localcloud.gateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.Get;
import com.localcloud.config.LocalCloudConfig;

/**
 * Minimal local metadata server surface for SDKs and applications that expect
 * Google Compute metadata values during local and CI runs.
 */
public class MetadataServerService {

    private static final String METADATA_FLAVOR = "Metadata-Flavor";
    private static final String LOCAL_TOKEN = "localcloud-dev-token";

    private final LocalCloudConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetadataServerService(LocalCloudConfig config) {
        this.config = config;
    }

    @Get("/")
    public HttpResponse root() {
        return text("instance/\nproject/\n");
    }

    @Get("/project/")
    public HttpResponse projectRoot() {
        return text("numeric-project-id\nproject-id\n");
    }

    @Get("/project/project-id")
    public HttpResponse projectId() {
        return text(config.getProjectId());
    }

    @Get("/project/numeric-project-id")
    public HttpResponse numericProjectId() {
        return text(String.valueOf(Integer.toUnsignedLong(config.getProjectId().hashCode())));
    }

    @Get("/instance/")
    public HttpResponse instanceRoot() {
        return text("region\nservice-accounts/\nzone\n");
    }

    @Get("/instance/region")
    public HttpResponse region() {
        return text("projects/" + numericProjectIdValue() + "/regions/" + localRegion());
    }

    @Get("/instance/zone")
    public HttpResponse zone() {
        return text("projects/" + numericProjectIdValue() + "/zones/" + localZone());
    }

    @Get("/instance/service-accounts/")
    public HttpResponse serviceAccountsRoot() {
        return text("default/\n");
    }

    @Get("/instance/service-accounts/default/")
    public HttpResponse defaultServiceAccountRoot() {
        return text("email\nscopes\ntoken\n");
    }

    @Get("/instance/service-accounts/default/email")
    public HttpResponse defaultServiceAccountEmail() {
        return text("default@" + config.getProjectId() + ".iam.gserviceaccount.com");
    }

    @Get("/instance/service-accounts/default/scopes")
    public HttpResponse defaultServiceAccountScopes() {
        return text("https://www.googleapis.com/auth/cloud-platform\n");
    }

    @Get("/instance/service-accounts/default/token")
    public HttpResponse defaultServiceAccountToken() {
        try {
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("access_token", LOCAL_TOKEN);
            token.put("expires_in", 3600);
            token.put("token_type", "Bearer");
            token.put("issued_at", Instant.now().toString());
            return json(token);
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "Metadata token generation failed");
        }
    }

    private long numericProjectIdValue() {
        return Integer.toUnsignedLong(config.getProjectId().hashCode());
    }

    private static String localRegion() {
        return env("LOCALCLOUD_REGION", "us-central1");
    }

    private static String localZone() {
        return env("LOCALCLOUD_ZONE", localRegion() + "-a");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private HttpResponse text(String value) {
        ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.PLAIN_TEXT_UTF_8)
                .add(METADATA_FLAVOR, "Google")
                .build();
        return HttpResponse.of(headers, HttpData.ofUtf8(value));
    }

    private HttpResponse json(Map<String, Object> body) throws Exception {
        ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.JSON)
                .add(METADATA_FLAVOR, "Google")
                .build();
        return HttpResponse.of(headers, HttpData.ofUtf8(mapper.writeValueAsString(body)));
    }
}
