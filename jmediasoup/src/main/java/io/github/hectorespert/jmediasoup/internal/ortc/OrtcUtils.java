/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Port of {@code handlers/ortc/utils.ts} of mediasoup-client, the ORTC helpers of the handlers. */
public final class OrtcUtils {

    private OrtcUtils() {}

    /**
     * The stream and track of an {@code a=msid}.
     *
     * @param streamId the stream id, or {@code -} for no stream
     * @param trackId the track id, or {@code null} if absent
     */
    public record MsidIds(String streamId, @Nullable String trackId) {}

    /**
     * Splits an {@code a=msid} value into its stream and track ids.
     *
     * @param msid the value, or {@code null}
     * @return the ids, or {@code null} if there is no stream id
     */
    public static @Nullable MsidIds getMsidStreamIdAndTrackId(@Nullable String msid) {
        if (msid == null) {
            return null;
        }

        // The stream id must be an id or '-' (no stream). The track id is optional.
        String[] parts = msid.trim().split("\\s+");

        if (parts[0].isEmpty()) {
            return null;
        }

        return new MsidIds(parts[0], parts.length > 1 ? parts[1] : null);
    }

    /**
     * Adds RTCP NACK support for the Opus codecs, which libwebrtc supports but does not announce.
     *
     * @param rtpCapabilities the native capabilities
     * @return the capabilities, with a {@code nack} feedback in each Opus codec that lacks it
     */
    public static RtpCapabilities addNackSupportForOpus(RtpCapabilities rtpCapabilities) {
        List<RtpCodecCapability> codecs = rtpCapabilities.codecs().stream()
                .map(codec -> {
                    String mimeType = codec.mimeType().toLowerCase(Locale.ROOT);
                    if ((!mimeType.equals("audio/opus") && !mimeType.equals("audio/multiopus"))
                            || codec.rtcpFeedback().stream()
                                    .anyMatch(fb -> fb.type().equals("nack")
                                            && (fb.parameter() == null
                                                    || fb.parameter().isEmpty()))) {
                        return codec;
                    }
                    List<RtcpFeedback> rtcpFeedback = new ArrayList<>(codec.rtcpFeedback());
                    rtcpFeedback.add(new RtcpFeedback("nack", null));
                    return new RtpCodecCapability(
                            codec.kind(),
                            codec.mimeType(),
                            codec.preferredPayloadType(),
                            codec.clockRate(),
                            codec.channels(),
                            codec.parameters(),
                            rtcpFeedback);
                })
                .toList();
        return new RtpCapabilities(codecs, rtpCapabilities.headerExtensions(), rtpCapabilities.fecMechanisms());
    }
}
