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
 * A codec that both the local endpoint and mediasoup support, with the payload types and parameters
 * of each side. Device computes it; it is only needed to implement a handler.
 *
 * @param kind the media kind
 * @param mimeType the codec MIME type, such as {@code audio/opus} or {@code video/VP8}
 * @param localPayloadType the payload type on the local side
 * @param localRtxPayloadType the RTX payload type on the local side, or {@code null} if absent
 * @param remotePayloadType the payload type on the mediasoup side
 * @param remoteRtxPayloadType the RTX payload type on the mediasoup side, or {@code null} if absent
 * @param clockRate the codec clock rate, in hertz
 * @param channels the number of channels, only for audio, or {@code null} if absent
 * @param localParameters the codec-specific parameters on the local side
 * @param remoteParameters the codec-specific parameters on the mediasoup side
 * @param rtcpFeedback the RTCP feedback messages both sides support
 */
public record ExtendedCodec(
        MediaKind kind,
        String mimeType,
        int localPayloadType,
        @Nullable Integer localRtxPayloadType,
        int remotePayloadType,
        @Nullable Integer remoteRtxPayloadType,
        int clockRate,
        @Nullable Integer channels,
        Map<String, Object> localParameters,
        Map<String, Object> remoteParameters,
        List<RtcpFeedback> rtcpFeedback) {

    /** Checks the fields and copies the collections, with absent ones as empty. */
    public ExtendedCodec {
        requireNonNull(kind, "kind");
        Checks.mimeType(mimeType);
        Checks.range(localPayloadType, 0, 127, "localPayloadType");
        Checks.range(localRtxPayloadType, 0, 127, "localRtxPayloadType");
        Checks.range(remotePayloadType, 0, 127, "remotePayloadType");
        Checks.range(remoteRtxPayloadType, 0, 127, "remoteRtxPayloadType");
        Checks.range(clockRate, 1, Integer.MAX_VALUE, "clockRate");
        Checks.range(channels, 1, Integer.MAX_VALUE, "channels");
        localParameters = Checks.parameters(localParameters, "localParameters");
        remoteParameters = Checks.parameters(remoteParameters, "remoteParameters");
        rtcpFeedback = Checks.list(rtcpFeedback, "rtcpFeedback");
    }
}
