/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.concurrent.CompletableFuture;

/**
 * Creates the handlers of a WebRTC stack and reports what the stack supports. A device has one
 * factory, and each transport gets its own handler from it.
 */
public interface HandlerFactory {

    /**
     * Returns the name of the handlers.
     *
     * @return a name that identifies the WebRTC stack, such as {@code WebRtcJavaHandler}
     */
    String name();

    /**
     * Returns the RTP capabilities of the stack in one direction, which may differ, for example when
     * it decodes codecs that it cannot encode.
     *
     * @param direction {@link TransportDirection#SEND} for sending and {@link TransportDirection#RECV}
     *     for receiving
     * @return a future with the capabilities, as the local SDP describes them
     */
    CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction);

    /**
     * Returns the SCTP capabilities of the stack.
     *
     * @return a future with the capabilities
     */
    CompletableFuture<SctpCapabilities> getNativeSctpCapabilities();

    /**
     * Creates the handler of a transport, ready to send or receive.
     *
     * @param options the parameters of the mediasoup transport and the listener of the handler
     * @return the handler
     */
    Handler create(HandlerOptions options);
}
