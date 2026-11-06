/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A STUN or TURN server, with the fields of the W3C {@code RTCIceServer}. The handler converts it to
 * the webrtc-java type.
 *
 * @param urls the server URLs, such as {@code turn:turn.example.com:3478}; at least one
 * @param username the TURN username, or {@code null} if absent
 * @param credential the TURN password, or {@code null} if absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record IceServer(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> urls,

        @Nullable String username,
        @Nullable String credential) {

    /** Checks the fields and copies the URLs. */
    public IceServer {
        urls = Checks.list(urls, "urls");
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("urls must not be empty");
        }
    }

    /**
     * Returns a server with one URL and no credentials, such as a STUN server.
     *
     * @param url the server URL
     * @return the server
     */
    public static IceServer of(String url) {
        return new IceServer(List.of(url), null, null);
    }

    /** Hides the credential, so that it does not end up in logs. */
    @Override
    public String toString() {
        return "IceServer[urls=" + urls + ", username=" + username + ", credential="
                + (credential == null ? null : "***") + "]";
    }
}
