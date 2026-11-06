/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import org.jspecify.annotations.Nullable;

/**
 * What to receive.
 *
 * @param trackId the identifier of the track, the id of the mediasoup consumer
 * @param kind the media kind
 * @param rtpParameters the RTP parameters that mediasoup sends
 * @param streamId the stream of the {@code a=msid} in the remote SDP, which the stack uses to
 *     synchronize the tracks that share it, or {@code null} for the stream of the producer
 */
public record HandlerReceiveOptions(
        String trackId,
        MediaKind kind,
        RtpParameters rtpParameters,
        @Nullable String streamId) {

    /** Checks the fields. */
    public HandlerReceiveOptions {
        Checks.notEmpty(trackId, "trackId");
        requireNonNull(kind, "kind");
        requireNonNull(rtpParameters, "rtpParameters");
    }
}
