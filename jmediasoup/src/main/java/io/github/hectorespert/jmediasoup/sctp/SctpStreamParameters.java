/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * The reliability of an SCTP stream.
 *
 * <p>A stream is either ordered and reliable, or unordered with at most one of
 * {@code maxPacketLifeTime} and {@code maxRetransmits}. Combinations that contradict this fail on
 * construction. An absent {@code ordered} means unordered if one of those limits is given, and
 * ordered otherwise.
 *
 * @param streamId the SCTP stream id, between 0 and 65534, or {@code null} until one is assigned
 * @param ordered whether messages are delivered in order, and therefore reliably, or {@code null} if
 *     absent
 * @param maxPacketLifeTime for unordered streams, the time in milliseconds after which a packet is
 *     no longer retransmitted, or {@code null} if absent
 * @param maxRetransmits for unordered streams, the maximum number of retransmissions of a packet, or
 *     {@code null} if absent
 * @param label the label that distinguishes the data channel from others, or {@code null} if absent
 * @param protocol the sub-protocol of the data channel, or {@code null} if absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SctpStreamParameters(
        @Nullable Integer streamId,
        @Nullable Boolean ordered,
        @Nullable Integer maxPacketLifeTime,
        @Nullable Integer maxRetransmits,
        @Nullable String label,
        @Nullable String protocol) {

    /** Checks the fields and rejects contradictory reliability settings. */
    public SctpStreamParameters {
        Checks.range(streamId, 0, 65534, "streamId");
        Checks.reliability(ordered, maxPacketLifeTime, maxRetransmits);
    }
}
