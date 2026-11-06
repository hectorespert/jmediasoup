/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The capabilities of a codec within the RTP capabilities.
 *
 * <p>There is one capability for each combination of parameters that needs its own payload type,
 * such as each H264 {@code packetization-mode} and {@code profile-level-id}, or each VP9
 * {@code profile-id}.
 *
 * @param kind the media kind
 * @param mimeType the codec MIME type, such as {@code audio/opus} or {@code video/VP8}
 * @param preferredPayloadType the preferred RTP payload type, or {@code null} if absent
 * @param clockRate the codec clock rate, in hertz
 * @param channels the number of channels (two for stereo), only for audio, or {@code null} if absent
 * @param parameters the codec-specific parameters, text or numbers
 * @param rtcpFeedback the supported RTCP feedback messages
 */
public record RtpCodecCapability(
        MediaKind kind,
        String mimeType,
        @Nullable Integer preferredPayloadType,
        int clockRate,
        @Nullable Integer channels,
        Map<String, Object> parameters,
        List<RtcpFeedback> rtcpFeedback)
        implements RtpCodec {

    /** Checks the fields and copies the collections, with absent ones as empty. */
    public RtpCodecCapability {
        requireNonNull(kind, "kind");
        Checks.mimeType(mimeType);
        Checks.range(preferredPayloadType, 0, 127, "preferredPayloadType");
        Checks.range(clockRate, 1, Integer.MAX_VALUE, "clockRate");
        Checks.range(channels, 1, Integer.MAX_VALUE, "channels");
        parameters = Checks.parameters(parameters, "parameters");
        rtcpFeedback = Checks.list(rtcpFeedback, "rtcpFeedback");
    }
}
