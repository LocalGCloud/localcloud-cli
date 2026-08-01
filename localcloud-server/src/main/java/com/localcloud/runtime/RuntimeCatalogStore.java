package com.localcloud.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic, file-backed catalog publication store. User runs retain resolved revisions. */
public final class RuntimeCatalogStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path file;
    private final AtomicReference<RuntimeProfileCatalog> catalog;

    public RuntimeCatalogStore(Path dataDirectory) {
        this.file = dataDirectory.resolve("runtime-profiles.json");
        this.catalog = new AtomicReference<>(RuntimeProfileCatalog.load(Files.isRegularFile(file) ? file : null));
    }

    public RuntimeProfileCatalog catalog() { return catalog.get(); }

    public synchronized RuntimeProfile importCandidate(RuntimeProfile candidate) {
        if (candidate.status() != RuntimeProfile.Status.CANDIDATE) {
            throw new IllegalArgumentException("imported profile must be a candidate");
        }
        RuntimeProfileCatalog current = catalog.get();
        if (current.find(candidate.revisionId()).isPresent()) {
            throw new IllegalArgumentException("profile revision already exists: " + candidate.revisionId());
        }
        List<RuntimeProfile> profiles = new ArrayList<>(current.all());
        profiles.add(candidate);
        replace(profiles, current.aliases());
        return candidate;
    }

    public synchronized RuntimeProfile publish(String revisionId, RuntimeProfile.Image image, String alias) {
        RuntimeProfileCatalog current = catalog.get();
        RuntimeProfile source = current.find(revisionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown profile revision: " + revisionId));
        if (source.status() != RuntimeProfile.Status.CANDIDATE) {
            throw new IllegalStateException("only candidate profile revisions can be published: " + revisionId);
        }
        RuntimeProfile published = new RuntimeProfile(source.id(), source.technology(), source.upstreamVersion(),
                source.revision(), RuntimeProfile.Status.PUBLISHED, image, source.build(), source.components(),
                source.capabilities(), source.environment(), source.properties(), source.limitations());
        List<RuntimeProfile> profiles = current.all().stream()
                .map(profile -> profile.revisionId().equals(revisionId) ? published : profile)
                .toList();
        Map<String, String> aliases = new LinkedHashMap<>(current.aliases());
        if (alias != null && !alias.isBlank()) aliases.put(alias, revisionId);
        replace(profiles, aliases);
        return published;
    }

    public synchronized RuntimeProfile deprecate(String revisionId) {
        RuntimeProfileCatalog current = catalog.get();
        RuntimeProfile source = current.find(revisionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown profile revision: " + revisionId));
        if (source.status() != RuntimeProfile.Status.PUBLISHED) {
            throw new IllegalStateException("only published profile revisions can be deprecated: " + revisionId);
        }
        RuntimeProfile deprecated = new RuntimeProfile(source.id(), source.technology(), source.upstreamVersion(),
                source.revision(), RuntimeProfile.Status.DEPRECATED, source.image(), source.build(), source.components(),
                source.capabilities(), source.environment(), source.properties(), source.limitations());
        List<RuntimeProfile> profiles = current.all().stream()
                .map(profile -> profile.revisionId().equals(revisionId) ? deprecated : profile)
                .toList();
        Map<String, String> aliases = new LinkedHashMap<>(current.aliases());
        aliases.entrySet().removeIf(entry -> entry.getValue().equals(revisionId));
        replace(profiles, aliases);
        return deprecated;
    }

    private void replace(List<RuntimeProfile> profiles, Map<String, String> aliases) {
        RuntimeProfileCatalog next = new RuntimeProfileCatalog(profiles, aliases);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "runtime-profiles-", ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    new RuntimeProfileCatalog.Document(next.all(), next.aliases()));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            catalog.set(next);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist runtime catalog", e);
        }
    }
}
