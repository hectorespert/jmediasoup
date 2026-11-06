/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The options to consume a producer. The first four fields come from the mediasoup consumer, so the
 * record can be read from the server response with the user's {@code ObjectMapper}.
 *
 * @param id the id of the mediasoup consumer
 * @param producerId the id of the producer it consumes
 * @param kind the media kind
 * @param rtpParameters the RTP parameters mediasoup sends
 * @param streamId the media stream id, shared by the tracks that should be played in sync, or
 *     {@code null} to use the producer id; from mediasoup-client
 * @param appData custom application data, which may contain {@code null} values
 */
public record ConsumerOptions(
        String id,
        String producerId,
        MediaKind kind,
        RtpParameters rtpParameters,
        @Nullable String streamId,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the application data, with absent data as empty. */
    public ConsumerOptions {
        Checks.notEmpty(id, "id");
        Checks.notEmpty(producerId, "producerId");
        requireNonNull(kind, "kind");
        requireNonNull(rtpParameters, "rtpParameters");
        appData = Checks.appData(appData);
    }
}
