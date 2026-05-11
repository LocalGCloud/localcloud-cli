package com.localcloud.emulators.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void symmetricEncryptDecryptRoundTrips() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("kms_round_trip");
        try {
            KmsRestService service = new KmsEmulator(testDataSource.getDataSource(), 8080).getRestService();
            body(service.createKeyRing(null, "local-project", "us-central1", "{\"keyRingId\":\"ring1\"}"));
            body(service.createCryptoKey(null, "local-project", "us-central1", "ring1", "{\"cryptoKeyId\":\"key1\"}"));

            String plaintext = Base64.getEncoder().encodeToString("hello kms".getBytes(StandardCharsets.UTF_8));
            var encryptJson = mapper.readTree(body(service.encrypt("local-project", "us-central1", "ring1", "key1",
                    "{\"plaintext\":\"" + plaintext + "\"}")));
            assertTrue(encryptJson.hasNonNull("ciphertext"));

            var decryptJson = mapper.readTree(body(service.decrypt("local-project", "us-central1", "ring1", "key1",
                    "{\"ciphertext\":\"" + encryptJson.get("ciphertext").asText() + "\"}")));
            String decrypted = new String(Base64.getDecoder().decode(decryptJson.get("plaintext").asText()), StandardCharsets.UTF_8);
            assertEquals("hello kms", decrypted);
        } finally {
            testDataSource.close();
        }
    }

    private String body(com.linecorp.armeria.common.HttpResponse response) {
        return response.aggregate().join().contentUtf8();
    }
}
