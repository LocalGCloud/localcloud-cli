package com.localcloud.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeProfileCatalogTest {
    @TempDir Path dataDir;

    @Test
    void requiresAdminPublicationBeforeResolutionAndPersistsAlias() {
        RuntimeCatalogStore store = new RuntimeCatalogStore(dataDir);
        RuntimeProfile candidate = store.catalog().find("dataproc:1.2-debian9@1").orElseThrow();
        assertEquals(RuntimeProfile.Status.CANDIDATE, candidate.status());
        assertThrows(IllegalArgumentException.class, () -> store.catalog().resolve(candidate.revisionId()));

        RuntimeProfile.Image image = new RuntimeProfile.Image(
                "localcloud/dataproc-runtime", "sha256:" + "a".repeat(64),
                List.of("localcloud"), "test-signature");
        RuntimeProfile published = store.publish(candidate.revisionId(), image, "dataproc:1.2");

        assertEquals(RuntimeProfile.Status.PUBLISHED, published.status());
        assertEquals(candidate.revisionId(), store.catalog().resolve("dataproc:1.2").revisionId());
        assertTrue(dataDir.resolve("runtime-profiles.json").toFile().isFile());
        assertEquals(candidate.revisionId(), new RuntimeCatalogStore(dataDir).catalog().resolve("dataproc:1.2").revisionId());
        assertThrows(IllegalStateException.class,
                () -> store.publish(candidate.revisionId(), new RuntimeProfile.Image(
                        "localcloud/replacement", "sha256:" + "c".repeat(64),
                        List.of("localcloud"), "replacement-signature"), "dataproc:1.2"));
        assertEquals("sha256:" + "a".repeat(64),
                store.catalog().resolve(candidate.revisionId()).image().digest());

        RuntimeProfile deprecated = store.deprecate(candidate.revisionId());
        assertEquals(RuntimeProfile.Status.DEPRECATED, deprecated.status());
        assertThrows(IllegalArgumentException.class, () -> store.catalog().resolve("dataproc:1.2"));
        assertThrows(IllegalStateException.class, () -> store.deprecate(candidate.revisionId()));
    }

    @Test
    void rejectsMutableOrUnapprovedImages() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProfile.Image(
                "docker.io/example/runtime", "latest", List.of("docker.io"), ""));
        RuntimeCatalogStore store = new RuntimeCatalogStore(dataDir);
        RuntimeProfile candidate = store.catalog().find("dataproc:1.2-debian9@1").orElseThrow();
        RuntimeProfile.Image image = new RuntimeProfile.Image(
                "unapproved.example/runtime", "sha256:" + "b".repeat(64), List.of("localcloud"), "");
        assertThrows(IllegalArgumentException.class, () -> store.publish(candidate.revisionId(), image, ""));
        assertThrows(IllegalStateException.class, () -> store.deprecate(candidate.revisionId()));
    }
}
