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
 * An RTP header extension that both the local endpoint and mediasoup support, with the identifier
 * of each direction. Device computes it; it is only needed to implement a handler.
 *
 * @param kind the media kind, or {@code null} if it applies to both
 * @param uri the URI of the header extension, as defined in RFC 5285
 * @param sendId the identifier when sending, between 1 and 255
 * @param recvId the identifier when receiving, between 1 and 255
 * @param encrypt whether the header is encrypted as per RFC 6904
 * @param direction the direction in which mediasoup supports it, or {@code null} if absent (send and
 *     receive)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtendedHeaderExtension(
        @Nullable MediaKind kind,
        String uri,
        @JsonProperty(required = true) int sendId,
        @JsonProperty(required = true) int recvId,
        @JsonProperty(required = true) boolean encrypt,
        @Nullable RtpHeaderExtensionDirection direction) {

    /** Checks the fields. */
    public ExtendedHeaderExtension {
        Checks.notEmpty(uri, "uri");
        Checks.range(sendId, 1, 255, "sendId");
        Checks.range(recvId, 1, 255, "recvId");
    }
}
