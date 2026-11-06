/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MediasoupServer.class)
class MediasoupServerTest {

    @Test
    void theServerStartsAndRejectsRequestsWithoutItsOrigin(MediasoupServer.Server server) throws Exception {
        try (HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(server.httpUrl() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(403, response.statusCode());
        }
        assertTrue(server.logs().contains("Server started"), server.logs());
    }
}
