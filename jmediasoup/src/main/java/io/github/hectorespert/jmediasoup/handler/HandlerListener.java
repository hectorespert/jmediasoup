/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import java.util.concurrent.CompletionStage;

/**
 * What a handler tells its transport. The transport implements it and passes it in {@link
 * HandlerOptions}; the handler may call it on any thread.
 */
public interface HandlerListener {

    /**
     * Asks the transport to connect the mediasoup side with the local DTLS parameters. The handler
     * calls it before the first send or receive, and waits for it before going on.
     *
     * @param dtlsParameters the local DTLS parameters, with the role of the local endpoint
     * @return a stage that completes when the mediasoup transport is connected, or fails if it could
     *     not be
     */
    CompletionStage<Void> connect(DtlsParameters dtlsParameters);

    /**
     * Reports a change in the connection state.
     *
     * @param connectionState the new state
     */
    void connectionStateChange(ConnectionState connectionState);

    /**
     * Reports a change in the ICE gathering state.
     *
     * @param iceGatheringState the new state
     */
    void iceGatheringStateChange(IceGatheringState iceGatheringState);

    /**
     * Reports an error while gathering an ICE candidate, such as a TURN server that does not answer.
     *
     * @param event the error
     */
    void iceCandidateError(RTCPeerConnectionIceErrorEvent event);
}
