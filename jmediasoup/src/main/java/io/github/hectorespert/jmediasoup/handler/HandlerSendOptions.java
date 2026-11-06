/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What to send.
 *
 * @param track the track
 * @param streamId the stream of the {@code a=msid} in the local SDP, which tells consuming endpoints
 *     which tracks to synchronize, or {@code null} for the default stream of the handler
 * @param encodings the encodings, such as the simulcast layers, or empty for a single default one
 * @param codecOptions the options of the codec, or {@code null}
 * @param codec the codec to use, or {@code null} for the first one the device supports
 */
public record HandlerSendOptions(
        MediaStreamTrack track,
        @Nullable String streamId,
        List<RtpEncodingParameters> encodings,
        @Nullable ProducerCodecOptions codecOptions,
        @Nullable RtpCodecCapability codec) {

    /** Checks the fields and copies the encodings, with absent ones as empty. */
    public HandlerSendOptions {
        requireNonNull(track, "track");
        encodings = Checks.list(encodings, "encodings");
    }
}
