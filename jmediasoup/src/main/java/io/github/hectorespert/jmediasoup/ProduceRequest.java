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
 * What a {@link ProduceHandler} passes to the server to create a producer.
 *
 * @param kind the media kind
 * @param rtpParameters the RTP parameters of the producer
 * @param appData the application data of the producer, which may contain {@code null} values
 */
public record ProduceRequest(MediaKind kind, RtpParameters rtpParameters, Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the application data. */
    public ProduceRequest {
        requireNonNull(kind, "kind");
        requireNonNull(rtpParameters, "rtpParameters");
        appData = Checks.appData(appData);
    }
}
