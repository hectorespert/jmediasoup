/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The settings of a codec within the RTP parameters.
 *
 * @param mimeType the codec MIME type, such as {@code audio/opus} or {@code video/VP8}
 * @param payloadType the value of the RTP payload type field, unique within the parameters
 * @param clockRate the codec clock rate, in hertz
 * @param channels the number of channels (two for stereo), only for audio, or {@code null} if absent
 * @param parameters the codec-specific parameters, text or numbers
 * @param rtcpFeedback the RTCP feedback messages in use
 */
public record RtpCodecParameters(
        String mimeType,
        int payloadType,
        int clockRate,
        @Nullable Integer channels,
        Map<String, Object> parameters,
        List<RtcpFeedback> rtcpFeedback)
        implements RtpCodec {

    /** Checks the fields and copies the collections, with absent ones as empty. */
    public RtpCodecParameters {
        Checks.mimeType(mimeType);
        Checks.range(payloadType, 0, 127, "payloadType");
        Checks.range(clockRate, 1, Integer.MAX_VALUE, "clockRate");
        Checks.range(channels, 1, Integer.MAX_VALUE, "channels");
        parameters = Checks.parameters(parameters, "parameters");
        rtcpFeedback = Checks.list(rtcpFeedback, "rtcpFeedback");
    }
}
