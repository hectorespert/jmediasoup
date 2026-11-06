/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.function.Function;

/**
 * A transport for sending media and data to mediasoup. {@link Device#createSendTransport} creates it.
 */
public final class SendTransport extends Transport {

    // The produce handler of the application, which produce() calls.
    @SuppressWarnings("unused")
    private final ProduceHandler produceHandler;

    SendTransport(
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            ConnectHandler connectHandler,
            ProduceHandler produceHandler) {
        super(TransportDirection.SEND, options, handlerFactory, getSendExtendedRtpCapabilities, connectHandler);
        this.produceHandler = requireNonNull(produceHandler, "produce");
    }
}
