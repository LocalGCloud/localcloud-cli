package com.localcloud.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Loads and validates immutable runtime profile revisions and their movable aliases. */
public final class RuntimeProfileCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Document(List<RuntimeProfile> profiles, Map<String, String> aliases) {}

    private final Map<String, RuntimeProfile> revisions;
    private final Map<String, String> aliases;

    public RuntimeProfileCatalog(List<RuntimeProfile> profiles, Map<String, String> aliases) {
        Map<String, RuntimeProfile> byRevision = new LinkedHashMap<>();
        for (RuntimeProfile profile : Objects.requireNonNull(profiles, "profiles")) {
            RuntimeProfile prior = byRevision.put(profile.revisionId(), profile);
            if (prior != null) throw new IllegalArgumentException("duplicate runtime profile " + profile.revisionId());
            validateRegistry(profile);
        }
        Map<String, String> checkedAliases = new LinkedHashMap<>();
        Objects.requireNonNullElse(aliases, Map.<String, String>of()).forEach((alias, revision) -> {
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("runtime alias is blank");
            RuntimeProfile target = byRevision.get(revision);
            if (target == null) throw new IllegalArgumentException("alias " + alias + " targets unknown revision " + revision);
            if (target.status() != RuntimeProfile.Status.PUBLISHED) {
                throw new IllegalArgumentException("alias " + alias + " targets unpublished revision " + revision);
            }
            checkedAliases.put(alias, revision);
        });
        this.revisions = Map.copyOf(byRevision);
        this.aliases = Map.copyOf(checkedAliases);
    }

    public static RuntimeProfileCatalog load(Path override) {
        try (InputStream input = override == null
                ? RuntimeProfileCatalog.class.getResourceAsStream("/runtime-profiles.json")
                : Files.newInputStream(override)) {
            if (input == null) throw new IllegalStateException("runtime-profiles.json is missing");
            Document document = MAPPER.readValue(input, new TypeReference<>() {});
            return new RuntimeProfileCatalog(document.profiles(), document.aliases());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load runtime profile catalog", e);
        }
    }

    public RuntimeProfile resolve(String selector) {
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("runtime profile selector is required");
        String revision = aliases.getOrDefault(selector, selector);
        RuntimeProfile profile = revisions.get(revision);
        if (profile == null) throw new IllegalArgumentException("unknown runtime profile: " + selector);
        if (profile.status() != RuntimeProfile.Status.PUBLISHED) {
            throw new IllegalArgumentException("runtime profile is not published: " + selector);
        }
        return profile;
    }

    public Optional<RuntimeProfile> find(String revisionId) {
        return Optional.ofNullable(revisions.get(revisionId));
    }

    public List<RuntimeProfile> published() {
        List<RuntimeProfile> result = new ArrayList<>();
        revisions.values().stream()
                .filter(profile -> profile.status() == RuntimeProfile.Status.PUBLISHED)
                .sorted(Comparator.comparing(RuntimeProfile::id).thenComparingInt(RuntimeProfile::revision))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public List<RuntimeProfile> all() {
        return revisions.values().stream()
                .sorted(Comparator.comparing(RuntimeProfile::id).thenComparingInt(RuntimeProfile::revision))
                .toList();
    }

    public Map<String, String> aliases() {
        return aliases;
    }

    private static void validateRegistry(RuntimeProfile profile) {
        if (!profile.image().executable()) {
            if (profile.status() == RuntimeProfile.Status.PUBLISHED) {
                throw new IllegalArgumentException(profile.revisionId() + " has no executable image");
            }
            return;
        }
        String reference = profile.image().reference();
        List<String> allowed = profile.image().allowedRegistries();
        if (allowed.isEmpty()) throw new IllegalArgumentException(profile.revisionId() + " has no allowed registry");
        boolean accepted = allowed.stream().anyMatch(registry -> reference.equals(registry) || reference.startsWith(registry + "/"));
        if (!accepted) throw new IllegalArgumentException(profile.revisionId() + " image registry is not allowed");
    }
}
