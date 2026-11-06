/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import java.util.concurrent.CompletionStage;

/**
 * Connects the mediasoup transport, the {@code connect} event of mediasoup-client. A transport calls
 * it once, before its first send or receive, with its local DTLS parameters, which the application
 * passes to {@code transport.connect()} on the server.
 *
 * <p>It runs on the executor of the transport, like the listeners. The transport waits for the stage
 * before going on, so the stage should complete when the server has answered.
 */
@FunctionalInterface
public interface ConnectHandler {

    /**
     * Connects the mediasoup transport.
     *
     * @param dtlsParameters the local DTLS parameters, to pass to the server
     * @return a stage that completes when the server has connected the transport, with any value,
     *     or fails if it could not; if the method throws, returns {@code null} or the stage fails, the
     *     operation that needed the connection fails with a {@link MediasoupClientException}
     */
    CompletionStage<?> connect(DtlsParameters dtlsParameters);
}
