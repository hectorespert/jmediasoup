/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCRtpSender;
import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import org.jspecify.annotations.Nullable;

/**
 * What a handler sends.
 *
 * @param localId the identifier of the sending within the handler
 * @param rtpParameters the RTP parameters to give mediasoup when producing
 * @param rtpSender the sender of the WebRTC stack, or {@code null} if the handler has none
 */
public record HandlerSendResult(
        String localId,
        RtpParameters rtpParameters,
        @Nullable RTCRtpSender rtpSender) {

    /** Checks the fields. */
    public HandlerSendResult {
        Checks.notEmpty(localId, "localId");
        requireNonNull(rtpParameters, "rtpParameters");
    }
}
