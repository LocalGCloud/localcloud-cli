package com.localcloud.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;

/**
 * Named snapshot API backed by seed-compatible YAML exports.
 */
public class SnapshotService {

    private final LocalCloudConfig config;
    private final ExportService exportService;
    private final SeedService seedService;
    private final ObjectMapper mapper;
    private final YAMLMapper yamlMapper;
    private final Path snapshotDir;

    public SnapshotService(LocalCloudConfig config, ExportService exportService, SeedService seedService) {
        this.config = config;
        this.exportService = exportService;
        this.seedService = seedService;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.yamlMapper = new YAMLMapper();
        this.snapshotDir = config.getDataDir().resolve("snapshots");
    }

    @Get("/snapshots")
    public HttpResponse listSnapshots() {
        try {
            Files.createDirectories(snapshotDir);
            List<Map<String, Object>> snapshots = new ArrayList<>();
            try (var stream = Files.list(snapshotDir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .sorted()
                        .forEach(path -> snapshots.add(snapshotMetadata(path)));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("snapshots", snapshots);
            response.put("count", snapshots.size());
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "List snapshots failed: " + e.getMessage());
        }
    }

    @Get("/snapshots/{name}")
    public HttpResponse getSnapshot(@Param("name") String name) {
        try {
            Path path = snapshotPath(name);
            if (!Files.exists(path)) {
                return error(HttpStatus.NOT_FOUND, "Snapshot not found: " + name);
            }
            return json(HttpStatus.OK, snapshotMetadata(path));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Read snapshot failed: " + e.getMessage());
        }
    }

    @Post("/snapshots")
    public HttpResponse createSnapshot(AggregatedHttpRequest request) {
        try {
            Map<String, Object> body = parseBody(request);
            String name = body.get("name") instanceof String rawName && !rawName.isBlank()
                    ? rawName
                    : defaultSnapshotName();
            Path path = snapshotPath(name);

            boolean replaceExisting = Boolean.TRUE.equals(body.get("replace_existing"));
            if (Files.exists(path) && !replaceExisting) {
                return error(HttpStatus.CONFLICT, "Snapshot already exists: " + name);
            }

            Set<String> services = parseServices(body.get("services"));
            String yaml = exportService.exportYaml(services);
            Files.createDirectories(snapshotDir);
            Files.writeString(path, yaml, StandardCharsets.UTF_8);

            Map<String, Object> response = snapshotMetadata(path);
            response.put("status", "created");
            return json(HttpStatus.CREATED, response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Create snapshot failed: " + e.getMessage());
        }
    }

    @Post("/snapshots/{name}/restore")
    public HttpResponse restoreSnapshot(@Param("name") String name, AggregatedHttpRequest request) {
        try {
            Path path = snapshotPath(name);
            if (!Files.exists(path)) {
                return error(HttpStatus.NOT_FOUND, "Snapshot not found: " + name);
            }

            Map<String, Object> body = parseBody(request);
            boolean replace = Boolean.TRUE.equals(body.get("replace"));
            boolean volatileOnly = Boolean.TRUE.equals(body.get("volatile_only"));

            String yaml = Files.readString(path, StandardCharsets.UTF_8);
            int cleared = replace ? seedService.resetProjectData(config.getProjectId()) : 0;
            Map<String, Object> seedResult = seedService.seedYaml(yaml, volatileOnly);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "restored");
            response.put("snapshot", name);
            response.put("replace", replace);
            response.put("rows_cleared", cleared);
            response.put("seed_result", seedResult);
            return json(HttpStatus.OK, response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Restore snapshot failed: " + e.getMessage());
        }
    }

    @Delete("/snapshots/{name}")
    public HttpResponse deleteSnapshot(@Param("name") String name) {
        try {
            Path path = snapshotPath(name);
            if (!Files.exists(path)) {
                return error(HttpStatus.NOT_FOUND, "Snapshot not found: " + name);
            }
            Files.delete(path);
            return json(HttpStatus.OK, Map.of("status", "deleted", "snapshot", name));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Delete snapshot failed: " + e.getMessage());
        }
    }

    private Map<String, Object> snapshotMetadata(Path path) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            String fileName = path.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ".yaml".length());
            metadata.put("name", name);
            metadata.put("created_at", Files.getLastModifiedTime(path).toInstant().toString());
            metadata.put("size_bytes", Files.size(path));

            @SuppressWarnings("unchecked")
            Map<String, Object> content = yamlMapper.readValue(Files.readString(path), Map.class);
            Object services = content.get("services");
            if (services instanceof Map<?, ?> serviceMap) {
                metadata.put("services", new ArrayList<>(serviceMap.keySet()));
            } else {
                metadata.put("services", List.of());
            }
            metadata.put("project", content.getOrDefault("project", config.getProjectId()));
            return metadata;
        } catch (Exception e) {
            return Map.of(
                    "name", path.getFileName().toString(),
                    "metadata_error", e.getMessage()
            );
        }
    }

    private Path snapshotPath(String name) {
        String safeName = validateName(name);
        return snapshotDir.resolve(safeName + ".yaml").normalize();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name is required");
        }
        if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Snapshot name may contain only letters, numbers, dot, underscore, and hyphen");
        }
        return name;
    }

    private static String defaultSnapshotName() {
        return "snapshot-" + Instant.now().toString()
                .replace(":", "")
                .replace(".", "-");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AggregatedHttpRequest request) throws Exception {
        String body = request.contentUtf8();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(body, Map.class);
    }

    private static Set<String> parseServices(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof List<?> list) {
            Set<String> services = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    services.add(String.valueOf(item).trim());
                }
            }
            return services;
        }
        return new LinkedHashSet<>(Arrays.stream(String.valueOf(raw).split(","))
                .map(String::trim)
                .filter(service -> !service.isEmpty())
                .toList());
    }

    private HttpResponse json(HttpStatus status, Object body) throws Exception {
        return HttpResponse.of(status, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }

    private HttpResponse error(HttpStatus status, String message) {
        try {
            return json(status, Map.of("error", true, "message", message));
        } catch (Exception e) {
            return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
