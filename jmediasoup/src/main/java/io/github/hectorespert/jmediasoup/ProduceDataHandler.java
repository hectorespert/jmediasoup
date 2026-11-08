/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import java.util.concurrent.CompletionStage;

/**
 * Creates the data producer on the server, the {@code producedata} event of mediasoup-client. A send
 * transport calls it on each {@code produceData}, with what the application passes to {@code
 * transport.produceData()} on the server, which returns the id of the new data producer.
 *
 * <p>It runs on the executor of the transport, like the listeners.
 */
@FunctionalInterface
public interface ProduceDataHandler {

    /**
     * Creates the data producer on the server.
     *
     * @param request the SCTP stream parameters, label, protocol and application data of the data
     *     producer
     * @return a stage with the id of the data producer on the server; if the method throws, returns
     *     {@code null}, the stage fails or completes with {@code null} or an empty id, the produceData
     *     fails with a {@link MediasoupClientException}
     */
    CompletionStage<String> produceData(ProduceDataRequest request);
}
