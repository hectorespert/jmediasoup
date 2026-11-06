/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import java.util.concurrent.CompletionStage;

/**
 * Creates the producer on the server, the {@code produce} event of mediasoup-client. A send transport
 * calls it on each {@code produce}, with what the application passes to {@code transport.produce()}
 * on the server, which returns the id of the new producer.
 *
 * <p>It runs on the executor of the transport, like the listeners.
 */
@FunctionalInterface
public interface ProduceHandler {

    /**
     * Creates the producer on the server.
     *
     * @param request the kind, RTP parameters and application data of the producer
     * @return a stage with the id of the producer on the server; if the method throws, returns {@code
     *     null}, the stage fails or completes with {@code null} or an empty id, the produce fails with a
     *     {@link MediasoupClientException}
     */
    CompletionStage<String> produce(ProduceRequest request);
}
