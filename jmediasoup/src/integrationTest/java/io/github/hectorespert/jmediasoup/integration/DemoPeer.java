/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static io.github.hectorespert.jmediasoup.integration.ProtooClient.MAPPER;

import io.github.hectorespert.jmediasoup.ConsumerOptions;
import io.github.hectorespert.jmediasoup.DataConsumerOptions;
import io.github.hectorespert.jmediasoup.ProduceDataRequest;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * A peer of a room of the mediasoup demo, with a method for each request of its protoo signaling that
 * the tests use. The server consumes for the peer every producer of the other peers once it has joined
 * and has a consumer transport, and tells it with a {@code newConsumer} request, which waits in
 * {@link #nextConsumer(Duration)} until the test accepts it. If the peer joined with SCTP
 * capabilities, the server also consumes the {@code chat} data producers of the other peers and the
 * one of its bot, with {@code newDataConsumer} requests, which wait in {@link
 * #nextDataConsumer(Duration)}.
 */
final class DemoPeer implements AutoCloseable {

    /** The direction of a WebRTC transport of the demo, in its {@code appData.direction}. */
    enum Direction {
        PRODUCER("producer"),
        CONSUMER("consumer");

        private final String value;

        Direction(String value) {
            this.value = value;
        }
    }

    /**
     * A consumer that the server has created, paused, for this peer. The server resumes it when the
     * peer accepts it, so accept it once the receive transport consumes it.
     *
     * @param options what the receive transport consumes
     * @param peerId the peer of the producer
     * @param accepted completes the {@code newConsumer} request
     */
    record NewConsumer(ConsumerOptions options, String peerId, CompletableFuture<Void> accepted) {

        /** Accepts the consumer, so that the server resumes it. */
        void accept() {
            accepted.complete(null);
        }
    }

    /**
     * A data consumer that the server has created for this peer, for a {@code chat} data producer of
     * another peer or for the one of the bot.
     *
     * @param options what the receive transport consumes
     * @param peerId the peer of the data producer, or {@code null} for the bot
     * @param channel {@code chat} or {@code bot}
     * @param accepted completes the {@code newDataConsumer} request
     */
    record NewDataConsumer(
            DataConsumerOptions options, String peerId, String channel, CompletableFuture<Void> accepted) {

        /** Answers the request of the server. */
        void accept() {
            accepted.complete(null);
        }
    }

    private final ProtooClient protoo;
    private final BlockingQueue<NewConsumer> newConsumers = new LinkedBlockingQueue<>();
    private final BlockingQueue<NewDataConsumer> newDataConsumers = new LinkedBlockingQueue<>();

    private DemoPeer(ProtooClient protoo) {
        this.protoo = protoo;
        protoo.onRequest("newConsumer", this::newConsumer);
        protoo.onRequest("newDataConsumer", this::newDataConsumer);
    }

    /**
     * Connects a peer to a room, which the server creates with its first peer. The peer has to
     * {@link #join} within 10 seconds, or the server closes it.
     *
     * @param server the server
     * @param roomId the room
     * @param peerId the peer
     * @return the connected peer
     */
    static DemoPeer connect(MediasoupServer.Server server, String roomId, String peerId) {
        URI url = URI.create(server.httpUrl().replaceFirst("^http", "ws")
                + "/?roomId=" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                + "&peerId=" + URLEncoder.encode(peerId, StandardCharsets.UTF_8));
        return new DemoPeer(ProtooClient.connect(url, MediasoupServer.Server.ORIGIN));
    }

    /**
     * {@code getRouterRtpCapabilities}.
     *
     * @return the RTP capabilities of the router
     */
    CompletableFuture<RtpCapabilities> routerRtpCapabilities() {
        return protoo.request("getRouterRtpCapabilities", null)
                .thenApply(data -> MAPPER.treeToValue(data.get("routerRtpCapabilities"), RtpCapabilities.class));
    }

    /**
     * {@code join}: the peer enters the room, and the server starts consuming the producers of the
     * other peers for it, if it has a consumer transport.
     *
     * @param displayName the name that the other peers see
     * @param rtpCapabilities the RTP capabilities to consume with, those of the device
     * @return the ids of the other peers
     */
    CompletableFuture<List<String>> join(String displayName, RtpCapabilities rtpCapabilities) {
        return join(displayName, rtpCapabilities, null);
    }

    /**
     * {@code join} with SCTP capabilities, so that the server also consumes data for the peer, if its
     * consumer transport has SCTP.
     *
     * @param displayName the name that the other peers see
     * @param rtpCapabilities the RTP capabilities to consume with, those of the device
     * @param sctpCapabilities the SCTP capabilities of the device, or {@code null} to consume no data
     * @return the ids of the other peers
     */
    CompletableFuture<List<String>> join(
            String displayName, RtpCapabilities rtpCapabilities, SctpCapabilities sctpCapabilities) {
        var request = new HashMap<String, Object>();
        request.put("displayName", displayName);
        request.put("device", Map.of("flag", "unknown", "name", "jmediasoup"));
        request.put("rtpCapabilities", rtpCapabilities);
        if (sctpCapabilities != null) {
            request.put("sctpCapabilities", sctpCapabilities);
        }
        return protoo.request("join", request)
                .thenApply(data -> data.get("peers")
                        .valueStream()
                        .map(peer -> peer.path("peerId").asString())
                        .toList());
    }

    /**
     * {@code createWebRtcTransport}, without SCTP.
     *
     * @param direction whether the peer produces or consumes on it
     * @return what the device needs to create the transport
     */
    CompletableFuture<TransportOptions> createWebRtcTransport(Direction direction) {
        return createWebRtcTransport(direction, null);
    }

    /**
     * {@code createWebRtcTransport}, with SCTP if there are SCTP capabilities.
     *
     * @param direction whether the peer produces or consumes on it
     * @param sctpCapabilities the SCTP capabilities of the device, or {@code null} for no SCTP
     * @return what the device needs to create the transport
     */
    CompletableFuture<TransportOptions> createWebRtcTransport(Direction direction, SctpCapabilities sctpCapabilities) {
        var request = new HashMap<String, Object>();
        request.put("forceTcp", false);
        request.put("appData", Map.of("direction", direction.value));
        if (sctpCapabilities != null) {
            request.put("sctpCapabilities", sctpCapabilities);
        }
        return protoo.request("createWebRtcTransport", request).thenApply(data -> {
            TransportOptions.Builder builder = TransportOptions.builder(
                    data.get("transportId").asString(),
                    MAPPER.treeToValue(data.get("iceParameters"), IceParameters.class),
                    MAPPER.treeToValue(data.get("iceCandidates"), new TypeReference<List<IceCandidate>>() {}),
                    MAPPER.treeToValue(data.get("dtlsParameters"), DtlsParameters.class));
            if (data.hasNonNull("sctpParameters")) {
                builder.sctpParameters(MAPPER.treeToValue(data.get("sctpParameters"), SctpParameters.class));
            }
            return builder.build();
        });
    }

    /**
     * {@code connectWebRtcTransport}, for the {@code connect} handler of a transport.
     *
     * @param transportId the transport
     * @param dtlsParameters the local DTLS parameters of the transport
     * @return completes when the server has connected it
     */
    CompletableFuture<Void> connectWebRtcTransport(String transportId, DtlsParameters dtlsParameters) {
        return protoo.request(
                        "connectWebRtcTransport", Map.of("transportId", transportId, "dtlsParameters", dtlsParameters))
                .thenApply(data -> null);
    }

    /**
     * {@code produce}: the server creates a producer, and a consumer of it for every other peer.
     *
     * @param transportId the producer transport
     * @param kind the kind
     * @param rtpParameters the RTP parameters that the peer sends with
     * @param source {@code audio}, {@code video} or {@code screensharing}
     * @return the id of the producer
     */
    CompletableFuture<String> produce(String transportId, MediaKind kind, RtpParameters rtpParameters, String source) {
        return protoo.request(
                        "produce",
                        Map.of(
                                "transportId", transportId,
                                "kind", kind,
                                "rtpParameters", rtpParameters,
                                "appData", Map.of("source", source)))
                .thenApply(data -> data.get("producerId").asString());
    }

    /**
     * {@code produceData}: the server creates a data producer and, for the {@code chat} channel, a
     * data consumer of it for every other peer.
     *
     * @param transportId the producer transport
     * @param request what the {@code produceData} handler of the transport passes
     * @param channel {@code chat} or {@code bot}
     * @return the id of the data producer
     */
    CompletableFuture<String> produceData(String transportId, ProduceDataRequest request, String channel) {
        return protoo.request(
                        "produceData",
                        Map.of(
                                "transportId", transportId,
                                "sctpStreamParameters", request.sctpStreamParameters(),
                                "label", request.label(),
                                "protocol", request.protocol(),
                                "appData", Map.of("channel", channel)))
                .thenApply(data -> data.get("dataProducerId").asString());
    }

    /**
     * {@code restartIce}: the server restarts ICE on a transport.
     *
     * @param transportId the transport
     * @return the new ICE parameters of the server
     */
    CompletableFuture<IceParameters> restartIce(String transportId) {
        return protoo.request("restartIce", Map.of("transportId", transportId))
                .thenApply(data -> MAPPER.treeToValue(data.get("iceParameters"), IceParameters.class));
    }

    /**
     * {@code closeProducer}, a notification in this demo: the server closes the producer without
     * answering.
     *
     * @param producerId the producer
     */
    void closeProducer(String producerId) {
        protoo.notify("closeProducer", Map.of("producerId", producerId));
    }

    /**
     * Waits for the server to create a consumer for this peer.
     *
     * @param timeout how long to wait
     * @return the consumer, not yet accepted
     * @throws TimeoutException if none arrives in time
     * @throws InterruptedException if interrupted while waiting
     */
    NewConsumer nextConsumer(Duration timeout) throws TimeoutException, InterruptedException {
        NewConsumer consumer = newConsumers.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (consumer == null) {
            throw new TimeoutException("no newConsumer request in " + timeout);
        }
        return consumer;
    }

    /**
     * Waits for the server to create a data consumer for this peer.
     *
     * @param timeout how long to wait
     * @return the data consumer, whose request is not yet answered
     * @throws TimeoutException if none arrives in time
     * @throws InterruptedException if interrupted while waiting
     */
    NewDataConsumer nextDataConsumer(Duration timeout) throws TimeoutException, InterruptedException {
        NewDataConsumer dataConsumer = newDataConsumers.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (dataConsumer == null) {
            throw new TimeoutException("no newDataConsumer request in " + timeout);
        }
        return dataConsumer;
    }

    /**
     * {@code getTransportStats}.
     *
     * @param transportId the transport
     * @return the stats of mediasoup, a list with one {@code WebRtcTransportStat}
     */
    CompletableFuture<JsonNode> transportStats(String transportId) {
        return protoo.request("getTransportStats", Map.of("transportId", transportId))
                .thenApply(data -> data.get("stats"));
    }

    /**
     * {@code getProducerStats}.
     *
     * @param producerId the producer
     * @return the stats of mediasoup, a list of {@code ProducerStat}, one per received stream
     */
    CompletableFuture<JsonNode> producerStats(String producerId) {
        return protoo.request("getProducerStats", Map.of("producerId", producerId))
                .thenApply(data -> data.get("stats"));
    }

    /**
     * {@code getConsumerStats}.
     *
     * @param consumerId the consumer
     * @return the stats of mediasoup, a list of {@code ConsumerStat}, of the sent stream and of the
     *     producer stream
     */
    CompletableFuture<JsonNode> consumerStats(String consumerId) {
        return protoo.request("getConsumerStats", Map.of("consumerId", consumerId))
                .thenApply(data -> data.get("stats"));
    }

    /** Leaves the room: the server closes the peer and its transports when the WebSocket closes. */
    @Override
    public void close() {
        protoo.close();
    }

    private CompletableFuture<Void> newConsumer(JsonNode data) {
        var options = new ConsumerOptions(
                data.get("consumerId").asString(),
                data.get("producerId").asString(),
                MAPPER.treeToValue(data.get("kind"), MediaKind.class),
                MAPPER.treeToValue(data.get("rtpParameters"), RtpParameters.class),
                null,
                MAPPER.treeToValue(data.get("appData"), new TypeReference<Map<String, Object>>() {}));
        var consumer = new NewConsumer(options, data.path("peerId").asString(), new CompletableFuture<>());
        newConsumers.add(consumer);
        return consumer.accepted();
    }

    private CompletableFuture<Void> newDataConsumer(JsonNode data) {
        var options = new DataConsumerOptions(
                data.get("dataConsumerId").asString(),
                data.get("dataProducerId").asString(),
                MAPPER.treeToValue(data.get("sctpStreamParameters"), SctpStreamParameters.class),
                data.path("label").asString(),
                data.path("protocol").asString(),
                MAPPER.treeToValue(data.get("appData"), new TypeReference<Map<String, Object>>() {}));
        var dataConsumer = new NewDataConsumer(
                options,
                data.hasNonNull("peerId") ? data.get("peerId").asString() : null,
                data.path("appData").path("channel").asString(),
                new CompletableFuture<>());
        newDataConsumers.add(dataConsumer);
        return dataConsumer.accepted();
    }
}
