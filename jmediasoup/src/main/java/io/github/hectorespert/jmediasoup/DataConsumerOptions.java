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
 * The options to consume a data producer. The first three fields come from the mediasoup data
 * consumer, so the record can be read from the server response with the user's
 * {@code ObjectMapper}.
 *
 * @param id the id of the mediasoup data consumer
 * @param dataProducerId the id of the data producer it consumes
 * @param sctpStreamParameters the SCTP stream mediasoup sends on
 * @param label the label of the data channel, or {@code null} if absent
 * @param protocol the sub-protocol of the data channel, or {@code null} if absent
 * @param appData custom application data, which may contain {@code null} values
 */
public record DataConsumerOptions(
        String id,
        String dataProducerId,
        SctpStreamParameters sctpStreamParameters,
        @Nullable String label,
        @Nullable String protocol,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the application data, with absent data as empty. */
    public DataConsumerOptions {
        Checks.notEmpty(id, "id");
        Checks.notEmpty(dataProducerId, "dataProducerId");
        requireNonNull(sctpStreamParameters, "sctpStreamParameters");
        appData = Checks.appData(appData);
    }
}
