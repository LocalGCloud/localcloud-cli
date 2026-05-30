package com.localcloud.emulators.iam;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.iam.v1.Policy;

/**
 * Shared REST handler for IAM policy operations (getIamPolicy, setIamPolicy, testIamPermissions).
 * Handles JSON serialization and delegates to {@link IAMRepository} for storage.
 * <p>
 * This is a permissive stub: testIamPermissions returns all requested permissions,
 * and setIamPolicy stores the policy but does NOT enforce it. A WARN log is emitted
 * on every setIamPolicy call to inform users.
 */
public class IAMPolicyRestHandler {
    private static final Logger logger = LoggerFactory.getLogger(IAMPolicyRestHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final IAMRepository repository;

    public IAMPolicyRestHandler(IAMRepository repository) {
        this.repository = repository;
    }

    /**
     * GET ...:getIamPolicy
     * Returns the stored policy as JSON, or an empty policy if none exists.
     */
    public String getIamPolicy(String resource) {
        try {
            Policy policy = repository.get(resource);
            return policyToJson(policy);
        } catch (Exception e) {
            logger.error("Failed to get IAM policy for {}", resource, e);
            throw new RuntimeException("Failed to get IAM policy", e);
        }
    }

    /**
     * POST ...:setIamPolicy
     * Stores the policy and returns the updated policy as JSON.
     * Emits a WARN log stating that IAM is not enforced in LocalCloud.
     */
    public String setIamPolicy(String resource, String requestBody) {
        logger.warn("IAM policy set for {} but NOT enforced in LocalCloud. " +
                "This is a permissive emulator — all requests are allowed.", resource);
        try {
            JsonNode requestJson = mapper.readTree(requestBody);
            JsonNode policyJson = requestJson.has("policy") ? requestJson.get("policy") : requestJson;
            Policy policy = jsonToPolicy(policyJson);
            Policy stored = repository.set(resource, policy);
            return policyToJson(stored);
        } catch (Exception e) {
            logger.error("Failed to set IAM policy for {}", resource, e);
            throw new RuntimeException("Failed to set IAM policy", e);
        }
    }

    /**
     * POST ...:testIamPermissions
     * Returns all requested permissions as granted (permissive behavior).
     */
    public String testIamPermissions(String resource, String requestBody) {
        try {
            JsonNode requestJson = mapper.readTree(requestBody);
            List<String> permissions = mapper.readerForListOf(String.class).readValue(requestJson.get("permissions"));
            ObjectNode result = mapper.createObjectNode();
            ArrayNode granted = mapper.createArrayNode();
            for (String permission : permissions) {
                granted.add(permission);
            }
            result.set("permissions", granted);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("Failed to test IAM permissions for {}", resource, e);
            throw new RuntimeException("Failed to test IAM permissions", e);
        }
    }

    // --- JSON <-> Proto conversion ---

    private String policyToJson(Policy policy) {
        ObjectNode result = mapper.createObjectNode();
        if (policy.getVersion() != 0) {
            result.put("version", policy.getVersion());
        }
        if (!policy.getBindingsList().isEmpty()) {
            ArrayNode bindings = mapper.createArrayNode();
            for (var binding : policy.getBindingsList()) {
                ObjectNode b = mapper.createObjectNode();
                b.put("role", binding.getRole());
                if (!binding.getMembersList().isEmpty()) {
                    ArrayNode members = mapper.createArrayNode();
                    for (String member : binding.getMembersList()) {
                        members.add(member);
                    }
                    b.set("members", members);
                }
                bindings.add(b);
            }
            result.set("bindings", bindings);
        }
        if (!policy.getEtag().isEmpty()) {
            result.put("etag", policy.getEtag().toStringUtf8());
        }
        return result.toString();
    }

    private Policy jsonToPolicy(JsonNode json) {
        Policy.Builder builder = Policy.newBuilder();
        if (json.has("version")) {
            builder.setVersion(json.get("version").asInt());
        }
        if (json.has("bindings") && json.get("bindings").isArray()) {
            for (JsonNode bindingJson : json.get("bindings")) {
                var bindingBuilder = com.google.iam.v1.Binding.newBuilder();
                if (bindingJson.has("role")) {
                    bindingBuilder.setRole(bindingJson.get("role").asText());
                }
                if (bindingJson.has("members") && bindingJson.get("members").isArray()) {
                    for (JsonNode member : bindingJson.get("members")) {
                        bindingBuilder.addMembers(member.asText());
                    }
                }
                builder.addBindings(bindingBuilder);
            }
        }
        if (json.has("etag")) {
            builder.setEtag(com.google.protobuf.ByteString.copyFromUtf8(json.get("etag").asText()));
        }
        return builder.build();
    }
}
