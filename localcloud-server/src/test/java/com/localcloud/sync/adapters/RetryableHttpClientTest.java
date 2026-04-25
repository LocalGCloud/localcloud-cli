package com.localcloud.sync.adapters;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryableHttpClientTest {

    @Test
    void canInstantiate() {
        RetryableHttpClient client = new RetryableHttpClient();
        assertNotNull(client);
    }

    @Test
    void invalidUrl_throwsIOException() {
        RetryableHttpClient client = new RetryableHttpClient();
        assertThrows(Exception.class, () -> client.get("http://localhost:99999/nonexistent", "token"));
    }
}
