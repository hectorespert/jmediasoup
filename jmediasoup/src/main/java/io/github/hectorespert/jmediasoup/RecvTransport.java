/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.function.Function;

/**
 * A transport for receiving media and data from mediasoup. {@link Device#createRecvTransport} creates
 * it.
 */
public final class RecvTransport extends Transport {

    RecvTransport(
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            ConnectHandler connectHandler) {
        super(TransportDirection.RECV, options, handlerFactory, getSendExtendedRtpCapabilities, connectHandler);
    }

    @Override
    void closeChildren() {
        // No consumers yet.
    }
}
