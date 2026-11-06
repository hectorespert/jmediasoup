/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IceTransportPolicy;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What a handler needs to know about its transport.
 *
 * @param direction whether the transport sends or receives
 * @param iceParameters the ICE parameters of the mediasoup transport
 * @param iceCandidates the ICE candidates of the mediasoup transport
 * @param dtlsParameters the DTLS parameters of the mediasoup transport
 * @param sctpParameters the SCTP parameters of the mediasoup transport, or {@code null} if it has no
 *     data channels
 * @param iceServers the STUN and TURN servers, empty if none
 * @param iceTransportPolicy the ICE transport policy, or {@code null} for the default of the stack
 * @param getSendExtendedRtpCapabilities computes the extended capabilities for sending from the
 *     native capabilities of a local offer
 * @param listener where the handler reports to its transport
 */
public record HandlerOptions(
        TransportDirection direction,
        IceParameters iceParameters,
        List<IceCandidate> iceCandidates,
        DtlsParameters dtlsParameters,
        @Nullable SctpParameters sctpParameters,
        List<IceServer> iceServers,
        @Nullable IceTransportPolicy iceTransportPolicy,
        Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
        HandlerListener listener) {

    /** Checks the fields and copies the lists, with absent ones as empty. */
    public HandlerOptions {
        requireNonNull(direction, "direction");
        requireNonNull(iceParameters, "iceParameters");
        iceCandidates = Checks.list(iceCandidates, "iceCandidates");
        requireNonNull(dtlsParameters, "dtlsParameters");
        iceServers = Checks.list(iceServers, "iceServers");
        requireNonNull(getSendExtendedRtpCapabilities, "getSendExtendedRtpCapabilities");
        requireNonNull(listener, "listener");
    }
}
