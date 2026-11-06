/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What a codec capability and the codec parameters have in common, which is what codec matching uses. */
public sealed interface RtpCodec permits RtpCodecCapability, RtpCodecParameters {

    /**
     * Returns the MIME type.
     *
     * @return the codec MIME type, such as {@code audio/opus} or {@code video/VP8}
     */
    String mimeType();

    /**
     * Returns the clock rate.
     *
     * @return the codec clock rate, in hertz
     */
    int clockRate();

    /**
     * Returns the number of channels.
     *
     * @return the number of channels (two for stereo), only for audio, or {@code null} if absent
     */
    @Nullable
    Integer channels();

    /**
     * Returns the codec-specific parameters.
     *
     * @return the parameters, in their original order; some of them, such as
     *     {@code packetization-mode} and {@code profile-level-id} in H264, are critical for codec
     *     matching
     */
    Map<String, Object> parameters();

    /**
     * Returns the supported RTCP feedback messages.
     *
     * @return the transport layer and codec-specific feedback messages
     */
    List<RtcpFeedback> rtcpFeedback();
}
