package com.localcloud.migration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MigrationSuite(
        String id,
        int revision,
        String name,
        String baselineProfile,
        List<String> targetProfiles,
        String capability,
        List<String> command,
        Map<String, String> environment,
        String fixtureYaml,
        List<Mount> mounts,
        List<String> outputPaths,
        List<Assertion> assertions,
        double performanceTolerance,
        long timeoutSeconds) {

    public record Mount(String source, String target, boolean readOnly) {}
    public enum AssertionType { OUTPUT_EXISTS, OUTPUT_SHA256, LOG_CONTAINS, LOG_NOT_CONTAINS, EMULATOR_SHA256 }
    public record Assertion(AssertionType type, String target, String expected) {
        public Assertion {
            type = Objects.requireNonNull(type, "assertion.type");
            target = require(target, "assertion.target");
            expected = Objects.requireNonNullElse(expected, "");
        }
    }


    public MigrationSuite {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        revision = revision < 1 ? 1 : revision;
        name = require(name, "name");
        baselineProfile = require(baselineProfile, "baselineProfile");
        targetProfiles = List.copyOf(Objects.requireNonNullElse(targetProfiles, List.of()));
        if (targetProfiles.isEmpty()) throw new IllegalArgumentException("targetProfiles is required");
        capability = require(capability, "capability");
        command = List.copyOf(Objects.requireNonNullElse(command, List.of()));
        if (command.isEmpty()) throw new IllegalArgumentException("command is required");
        environment = Map.copyOf(Objects.requireNonNullElse(environment, Map.of()));
        fixtureYaml = Objects.requireNonNullElse(fixtureYaml, "");
        mounts = List.copyOf(Objects.requireNonNullElse(mounts, List.of()));
        outputPaths = List.copyOf(Objects.requireNonNullElse(outputPaths, List.of()));
        assertions = List.copyOf(Objects.requireNonNullElse(assertions, List.of()));
        performanceTolerance = performanceTolerance <= 0 ? 2.0 : performanceTolerance;
        timeoutSeconds = timeoutSeconds <= 0 ? 3600 : timeoutSeconds;
    }

    public String revisionId() { return id + "@" + revision; }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
