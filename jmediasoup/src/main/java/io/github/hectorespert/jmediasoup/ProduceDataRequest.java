/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link ProduceDataHandler} passes to the server to create a data producer.
 *
 * @param sctpStreamParameters the stream id and reliability of the data channel
 * @param label the label of the data channel, empty if none
 * @param protocol the sub-protocol of the data channel, empty if none
 * @param appData the application data of the data producer, which may contain {@code null} values
 */
public record ProduceDataRequest(
        SctpStreamParameters sctpStreamParameters,
        String label,
        String protocol,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the application data. */
    public ProduceDataRequest {
        requireNonNull(sctpStreamParameters, "sctpStreamParameters");
        requireNonNull(label, "label");
        requireNonNull(protocol, "protocol");
        appData = Checks.appData(appData);
    }
}
