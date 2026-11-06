/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * A supported RTP header extension within the RTP capabilities.
 *
 * @param kind the media kind, or {@code null} if it applies to both
 * @param uri the URI of the header extension, as defined in RFC 5285
 * @param preferredId the preferred identifier in the RTP packet, between 1 and 255
 * @param preferredEncrypt whether the header should be encrypted as per RFC 6904, or {@code null} if
 *     absent (not encrypted)
 * @param direction the direction in which mediasoup supports it, or {@code null} if absent (send and
 *     receive)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RtpHeaderExtension(
        @Nullable MediaKind kind,
        String uri,
        @JsonProperty(required = true) int preferredId,
        @Nullable Boolean preferredEncrypt,
        @Nullable RtpHeaderExtensionDirection direction) {

    /** Checks the fields. */
    public RtpHeaderExtension {
        Checks.notEmpty(uri, "uri");
        Checks.range(preferredId, 1, 255, "preferredId");
    }
}
