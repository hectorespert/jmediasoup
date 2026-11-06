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

/** Port of {@code handlers/ortc/utils.ts} of mediasoup-client, the ORTC helpers of the handlers. */
public final class OrtcUtils {

    private OrtcUtils() {}

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
