package com.localcloud.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, administrator-published description of an executable technology stack. */
public record RuntimeProfile(
        String id,
        String technology,
        String upstreamVersion,
        int revision,
        Status status,
        Image image,
        BuildSource build,
        Map<String, String> components,
        Set<String> capabilities,
        Map<String, String> environment,
        Map<String, String> properties,
        List<String> limitations) {

    public enum Status { CANDIDATE, PUBLISHED, DEPRECATED }

    public record Image(String reference, String digest, List<String> allowedRegistries, String signature) {
        public Image {
            reference = Objects.requireNonNullElse(reference, "").trim();
            digest = Objects.requireNonNullElse(digest, "").trim().toLowerCase();
            if (!digest.isEmpty() && !digest.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("image.digest must be an immutable sha256 digest");
            }
            allowedRegistries = List.copyOf(Objects.requireNonNullElse(allowedRegistries, List.of()));
            signature = Objects.requireNonNullElse(signature, "");
        }

        public boolean executable() {
            return !reference.isBlank() && !digest.isBlank() && !allowedRegistries.isEmpty();
        }

        public String immutableReference() {
            if (!executable()) throw new IllegalStateException("runtime image has not been published");
            return reference.contains("@") ? reference : reference + "@" + digest;
        }
    }

    public record BuildSource(String recipe, String recipeDigest, String baseImage, String baseDigest) {
        public BuildSource {
            recipe = Objects.requireNonNullElse(recipe, "");
            recipeDigest = Objects.requireNonNullElse(recipeDigest, "");
            baseImage = Objects.requireNonNullElse(baseImage, "");
            baseDigest = Objects.requireNonNullElse(baseDigest, "");
        }
    }

    public RuntimeProfile {
        id = requireText(id, "id");
        technology = requireText(technology, "technology");
        upstreamVersion = requireText(upstreamVersion, "upstreamVersion");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        status = Objects.requireNonNull(status, "status");
        image = Objects.requireNonNullElseGet(image, () -> new Image("", "", List.of(), ""));
        build = Objects.requireNonNullElseGet(build, () -> new BuildSource("", "", "", ""));
        if (status == Status.PUBLISHED && (!image.executable() || image.signature().isBlank())) {
            throw new IllegalArgumentException("published runtime profile requires an immutable approved image and signature evidence");
        }
        components = Map.copyOf(Objects.requireNonNullElse(components, Map.of()));
        capabilities = Set.copyOf(Objects.requireNonNullElse(capabilities, Set.of()));
        environment = Map.copyOf(Objects.requireNonNullElse(environment, Map.of()));
        properties = Map.copyOf(Objects.requireNonNullElse(properties, Map.of()));
        limitations = List.copyOf(Objects.requireNonNullElse(limitations, List.of()));
    }

    public String revisionId() {
        return id + "@" + revision;
    }

    public boolean supports(String capability) {
        return capabilities.contains(capability);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

}
