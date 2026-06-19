package com.localcloud.emulators.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.admin.UnsupportedOperationResponses;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * REST implementation of the Cloud KMS v1 surface used by local dev workflows.
 */
public class KmsRestService {

    private static final byte[] CIPHERTEXT_MAGIC = "LCKMS1".getBytes(StandardCharsets.US_ASCII);
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RNG = new SecureRandom();

    private final KmsStore store;
    private final KmsEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IAMPolicyRestHandler iamHandler;

    public KmsRestService(KmsStore store, KmsEmulator emulator) {
        this(store, emulator, null);
    }

    public KmsRestService(KmsStore store, KmsEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/locations/{location}/keyRings")
    public HttpResponse createKeyRing(ServiceRequestContext ctx, @Param String project,
                                      @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            String keyRingId = firstNonBlank(ctx != null ? ctx.queryParams().get("keyRingId") : null,
                    readText(body, "keyRingId"), readText(body, "name"));
            if (isBlank(keyRingId)) {
                return error(HttpStatus.BAD_REQUEST, "Missing required parameter: keyRingId");
            }
            keyRingId = leaf(keyRingId);
            store.createKeyRing(project, location, keyRingId);
            return json(HttpStatus.OK, keyRingJson(project, location, keyRingId, null));
        } catch (Exception e) {
            return exception(e, "create key ring");
        }
    }

    @Get("/projects/{project}/locations/{location}/keyRings")
    public HttpResponse listKeyRings(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode items = out.putArray("keyRings");
            for (Map<String, Object> row : store.listKeyRings(project, location)) {
                items.add(keyRingJson(project, location, String.valueOf(row.get("key_ring_id")), row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list key rings");
        }
    }

    @Get("/projects/{project}/locations/{location}/keyRings/{keyRing}")
    public HttpResponse getKeyRing(@Param String project, @Param String location, @Param String keyRing) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getKeyRing(project, location, keyRing);
            if (row == null) {
                return error(HttpStatus.NOT_FOUND, "Key ring not found: " + keyRing);
            }
            return json(HttpStatus.OK, keyRingJson(project, location, keyRing, row));
        } catch (Exception e) {
            return exception(e, "get key ring");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys")
    public HttpResponse createCryptoKey(ServiceRequestContext ctx, @Param String project, @Param String location,
                                        @Param String keyRing, String body) {
        emulator.incrementRequestCount();
        try {
            if (store.getKeyRing(project, location, keyRing) == null) {
                return error(HttpStatus.NOT_FOUND, "Key ring not found: " + keyRing);
            }
            JsonNode root = readTree(body);
            String cryptoKeyId = firstNonBlank(ctx != null ? ctx.queryParams().get("cryptoKeyId") : null,
                    text(root, "cryptoKeyId"), text(root, "name"));
            if (isBlank(cryptoKeyId)) {
                return error(HttpStatus.BAD_REQUEST, "Missing required parameter: cryptoKeyId");
            }
            cryptoKeyId = leaf(cryptoKeyId);
            String purpose = firstNonBlank(text(root, "purpose"), "ENCRYPT_DECRYPT");
            String algorithm = root.path("versionTemplate").path("algorithm").asText("GOOGLE_SYMMETRIC_ENCRYPTION");
            String labelsJson = root.has("labels") ? mapper.writeValueAsString(root.get("labels")) : "{}";
            store.createCryptoKey(project, location, keyRing, cryptoKeyId, purpose, algorithm, labelsJson);
            return json(HttpStatus.OK, cryptoKeyJson(project, location, keyRing, cryptoKeyId,
                    store.getCryptoKey(project, location, keyRing, cryptoKeyId)));
        } catch (Exception e) {
            return exception(e, "create crypto key");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/importJobs")
    public HttpResponse createImportJob(@Param String project, @Param String location,
                                        @Param String keyRing, String body) {
        emulator.incrementRequestCount();
        return UnsupportedOperationResponses.rest("kms", "importJobs.create", "rest",
                "Use local key rings and crypto keys without import jobs.");
    }

    @Get("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys")
    public HttpResponse listCryptoKeys(@Param String project, @Param String location, @Param String keyRing) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode items = out.putArray("cryptoKeys");
            for (Map<String, Object> row : store.listCryptoKeys(project, location, keyRing)) {
                items.add(cryptoKeyJson(project, location, keyRing, String.valueOf(row.get("crypto_key_id")), row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list crypto keys");
        }
    }

    @Get("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}")
    public HttpResponse getCryptoKey(@Param String project, @Param String location, @Param String keyRing,
                                     @Param String cryptoKey) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getCryptoKey(project, location, keyRing, cryptoKey);
            if (row == null) {
                return error(HttpStatus.NOT_FOUND, "Crypto key not found: " + cryptoKey);
            }
            return json(HttpStatus.OK, cryptoKeyJson(project, location, keyRing, cryptoKey, row));
        } catch (Exception e) {
            return exception(e, "get crypto key");
        }
    }

    @Get("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions")
    public HttpResponse listCryptoKeyVersions(@Param String project, @Param String location, @Param String keyRing,
                                              @Param String cryptoKey) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode items = out.putArray("cryptoKeyVersions");
            for (Map<String, Object> row : store.listVersions(project, location, keyRing, cryptoKey)) {
                items.add(versionJson(project, location, keyRing, cryptoKey, row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list key versions");
        }
    }

    @Get("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}")
    public HttpResponse getCryptoKeyVersion(@Param String project, @Param String location, @Param String keyRing,
                                            @Param String cryptoKey, @Param String version) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getVersion(project, location, keyRing, cryptoKey, Integer.parseInt(version));
            if (row == null) {
                return error(HttpStatus.NOT_FOUND, "Crypto key version not found: " + version);
            }
            return json(HttpStatus.OK, versionJson(project, location, keyRing, cryptoKey, row));
        } catch (Exception e) {
            return exception(e, "get key version");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt")
    public HttpResponse encrypt(@Param String project, @Param String location, @Param String keyRing,
                                @Param String cryptoKey, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            byte[] plaintext = Base64.getDecoder().decode(requiredText(root, "plaintext"));
            byte[] aad = base64OrEmpty(root.path("additionalAuthenticatedData").asText(""));
            Map<String, Object> version = store.getPrimaryVersion(project, location, keyRing, cryptoKey);
            if (version == null) {
                return error(HttpStatus.NOT_FOUND, "Crypto key not found: " + cryptoKey);
            }
            if (!"ENABLED".equals(version.get("state"))) {
                return error(HttpStatus.FAILED_DEPENDENCY, "Primary crypto key version is not ENABLED");
            }
            int versionNumber = ((Number) version.get("version_number")).intValue();
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec((byte[]) version.get("key_material"), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteBuffer buffer = ByteBuffer.allocate(CIPHERTEXT_MAGIC.length + Integer.BYTES + iv.length + encrypted.length);
            buffer.put(CIPHERTEXT_MAGIC).putInt(versionNumber).put(iv).put(encrypted);

            ObjectNode out = mapper.createObjectNode();
            out.put("name", versionName(project, location, keyRing, cryptoKey, versionNumber));
            out.put("ciphertext", Base64.getEncoder().encodeToString(buffer.array()));
            out.put("protectionLevel", "SOFTWARE");
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "encrypt");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:decrypt")
    public HttpResponse decrypt(@Param String project, @Param String location, @Param String keyRing,
                                @Param String cryptoKey, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            byte[] ciphertext = Base64.getDecoder().decode(requiredText(root, "ciphertext"));
            byte[] aad = base64OrEmpty(root.path("additionalAuthenticatedData").asText(""));
            ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
            byte[] magic = new byte[CIPHERTEXT_MAGIC.length];
            buffer.get(magic);
            if (!java.util.Arrays.equals(CIPHERTEXT_MAGIC, magic)) {
                return error(HttpStatus.BAD_REQUEST, "Ciphertext was not produced by LocalCloud KMS");
            }
            int versionNumber = buffer.getInt();
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Map<String, Object> version = store.getVersion(project, location, keyRing, cryptoKey, versionNumber);
            if (version == null || !"ENABLED".equals(version.get("state"))) {
                return error(HttpStatus.FAILED_DEPENDENCY, "Crypto key version is not available for decrypt");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec((byte[]) version.get("key_material"), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] plaintext = cipher.doFinal(encrypted);

            ObjectNode out = mapper.createObjectNode();
            out.put("plaintext", Base64.getEncoder().encodeToString(plaintext));
            out.put("protectionLevel", "SOFTWARE");
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "decrypt");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:updateCryptoKeyPrimaryVersion")
    public HttpResponse updatePrimaryVersion(@Param String project, @Param String location, @Param String keyRing,
                                             @Param String cryptoKey, String body) {
        emulator.incrementRequestCount();
        try {
            int version = Integer.parseInt(requiredText(readTree(body), "cryptoKeyVersionId"));
            if (!store.setPrimaryVersion(project, location, keyRing, cryptoKey, version)) {
                return error(HttpStatus.NOT_FOUND, "Crypto key version not found: " + version);
            }
            return json(HttpStatus.OK, cryptoKeyJson(project, location, keyRing, cryptoKey,
                    store.getCryptoKey(project, location, keyRing, cryptoKey)));
        } catch (Exception e) {
            return exception(e, "update primary version");
        }
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:destroy")
    public HttpResponse destroyVersion(@Param String project, @Param String location, @Param String keyRing,
                                       @Param String cryptoKey, @Param String version) {
        return updateVersionState(project, location, keyRing, cryptoKey, version, "DESTROYED");
    }

    @Post("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:restore")
    public HttpResponse restoreVersion(@Param String project, @Param String location, @Param String keyRing,
                                       @Param String cryptoKey, @Param String version) {
        return updateVersionState(project, location, keyRing, cryptoKey, version, "ENABLED");
    }

    @Delete("/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}")
    public HttpResponse deleteVersion(@Param String project, @Param String location, @Param String keyRing,
                                      @Param String cryptoKey, @Param String version) {
        return updateVersionState(project, location, keyRing, cryptoKey, version, "DESTROYED");
    }

    private HttpResponse updateVersionState(String project, String location, String keyRing, String cryptoKey,
                                            String versionText, String state) {
        emulator.incrementRequestCount();
        try {
            int version = Integer.parseInt(versionText);
            if (!store.updateVersionState(project, location, keyRing, cryptoKey, version, state)) {
                return error(HttpStatus.NOT_FOUND, "Crypto key version not found: " + version);
            }
            return json(HttpStatus.OK, versionJson(project, location, keyRing, cryptoKey,
                    store.getVersion(project, location, keyRing, cryptoKey, version)));
        } catch (Exception e) {
            return exception(e, "update key version state");
        }
    }

    private ObjectNode keyRingJson(String project, String location, String keyRing, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        out.put("name", "projects/" + project + "/locations/" + location + "/keyRings/" + keyRing);
        if (row != null && row.get("created_at") != null) {
            out.put("createTime", String.valueOf(row.get("created_at")));
        }
        return out;
    }

    private ObjectNode cryptoKeyJson(String project, String location, String keyRing, String cryptoKey, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        out.put("name", keyName(project, location, keyRing, cryptoKey));
        out.put("purpose", row != null ? String.valueOf(row.get("purpose")) : "ENCRYPT_DECRYPT");
        ObjectNode primary = out.putObject("primary");
        int version = row != null && row.get("primary_version") instanceof Number n ? n.intValue() : 1;
        primary.put("name", versionName(project, location, keyRing, cryptoKey, version));
        primary.put("state", "ENABLED");
        ObjectNode template = out.putObject("versionTemplate");
        template.put("algorithm", row != null ? String.valueOf(row.get("algorithm")) : "GOOGLE_SYMMETRIC_ENCRYPTION");
        template.put("protectionLevel", "SOFTWARE");
        if (row != null && row.get("created_at") != null) {
            out.put("createTime", String.valueOf(row.get("created_at")));
        }
        return out;
    }

    private ObjectNode versionJson(String project, String location, String keyRing, String cryptoKey, Map<String, Object> row) {
        if (row == null) return mapper.createObjectNode();
        int version = ((Number) row.get("version_number")).intValue();
        ObjectNode out = mapper.createObjectNode();
        out.put("name", versionName(project, location, keyRing, cryptoKey, version));
        out.put("state", String.valueOf(row.get("state")));
        out.put("algorithm", String.valueOf(row.get("algorithm")));
        out.put("protectionLevel", "SOFTWARE");
        if (row.get("created_at") != null) {
            out.put("createTime", String.valueOf(row.get("created_at")));
        }
        return out;
    }

    private String keyName(String project, String location, String keyRing, String cryptoKey) {
        return "projects/" + project + "/locations/" + location + "/keyRings/" + keyRing + "/cryptoKeys/" + cryptoKey;
    }

    private String versionName(String project, String location, String keyRing, String cryptoKey, int version) {
        return keyName(project, location, keyRing, cryptoKey) + "/cryptoKeyVersions/" + version;
    }

    private JsonNode readTree(String body) throws Exception {
        return isBlank(body) ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private String readText(String body, String field) {
        try {
            return text(readTree(body), field);
        } catch (Exception e) {
            return "";
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.has(field) ? node.get(field).asText("") : "";
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (isBlank(value)) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private byte[] base64OrEmpty(String value) {
        return isBlank(value) ? new byte[0] : Base64.getDecoder().decode(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private String leaf(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private HttpResponse json(HttpStatus status, JsonNode node) {
        return HttpResponse.of(status, MediaType.JSON, node.toString());
    }

    private HttpResponse exception(Exception e, String action) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, "Failed to " + action + ": " + e.getMessage());
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

    private HttpResponse error(HttpStatus status, String message) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode inner = out.putObject("error");
        inner.put("code", status.code());
        inner.put("message", message);
        inner.put("status", status.reasonPhrase().replace(' ', '_').toUpperCase());
        return HttpResponse.of(status, MediaType.JSON, out.toString());
    }
}
