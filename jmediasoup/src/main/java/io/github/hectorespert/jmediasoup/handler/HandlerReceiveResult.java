/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * What a handler receives.
 *
 * @param localId the identifier of the receiving within the handler
 * @param track the remote track
 * @param rtpReceiver the receiver of the WebRTC stack, or {@code null} if the handler has none
 */
public record HandlerReceiveResult(
        String localId, MediaStreamTrack track, @Nullable RTCRtpReceiver rtpReceiver) {

    /** Checks the fields. */
    public HandlerReceiveResult {
        Checks.notEmpty(localId, "localId");
        requireNonNull(track, "track");
    }
}
