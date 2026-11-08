/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelResult;
import io.github.hectorespert.jmediasoup.handler.HandlerSendOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendResult;
import io.github.hectorespert.jmediasoup.internal.event.AsyncHandlers;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transport for sending media and data to mediasoup. {@link Device#createSendTransport} creates it.
 */
public final class SendTransport extends Transport {

    private static final Logger logger = LoggerFactory.getLogger(SendTransport.class);

    // The produce handler of the application.
    private final ProduceHandler produceHandler;
    // The produceData handler of the application, if it produces data.
    private final @Nullable ProduceDataHandler produceDataHandler;
    // Whether we can produce audio/video based on computed extended RTP capabilities.
    private final Map<MediaKind, Boolean> canProduceByKind;
    // Map of Producers indexed by id.
    private final Map<String, Producer> producers = new ConcurrentHashMap<>();
    // Map of DataProducers indexed by id.
    private final Map<String, DataProducer> dataProducers = new ConcurrentHashMap<>();

    SendTransport(
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            Map<MediaKind, Boolean> canProduceByKind,
            ConnectHandler connectHandler,
            ProduceHandler produceHandler,
            @Nullable ProduceDataHandler produceDataHandler) {
        super(TransportDirection.SEND, options, handlerFactory, getSendExtendedRtpCapabilities, connectHandler);
        this.canProduceByKind = Map.copyOf(canProduceByKind);
        this.produceHandler = requireNonNull(produceHandler, "produce");
        this.produceDataHandler = produceDataHandler;
    }

    /**
     * Sends a track to mediasoup. The handler negotiates the sending, connecting the transport first
     * if needed, and the {@link ProduceHandler} creates the producer on the server.
     *
     * <p>Productions run one after another, after the other operations of the transport.
     *
     * @param options the track and how to send it
     * @return a future with the producer; it fails with an {@link InvalidStateException} if the
     *     transport is closed, before or while producing, or if the track has ended, with an {@link
     *     UnsupportedException} if the device cannot send media of the kind of the track, with a {@link
     *     MediasoupClientException} if the {@link ProduceHandler} fails, or with the failure of the
     *     handler
     */
    public CompletableFuture<Producer> produce(ProducerOptions options) {
        requireNonNull(options, "options");
        MediaStreamTrack track = options.track();
        logger.debug("produce() [track:{}]", track.getId());

        MediaKind kind;
        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        }
        try {
            kind = MediaKind.fromValue(track.getKind());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (!canProduceByKind.getOrDefault(kind, false)) {
            return CompletableFuture.failedFuture(new UnsupportedException("cannot produce " + kind.value()));
        } else if (track.getState() == MediaStreamTrackState.ENDED) {
            return CompletableFuture.failedFuture(new InvalidStateException("track ended"));
        }

        return queue().push(
                        "transport.produce()",
                        () -> handler()
                                .send(new HandlerSendOptions(
                                        track,
                                        options.streamId(),
                                        normalizeEncodings(options.encodings()),
                                        options.codecOptions(),
                                        options.codec()))
                                .thenComposeAsync(result -> createProducer(options, kind, result), executor()));
    }

    // Asks the application to create the producer on the server, and creates the local one.
    private CompletableFuture<Producer> createProducer(
            ProducerOptions options, MediaKind kind, HandlerSendResult result) {
        return AsyncHandlers.<String>call(
                        "produce",
                        () -> produceHandler.produce(
                                new ProduceRequest(kind, result.rtpParameters(), options.appData())))
                .thenApply(id -> {
                    if (id.isEmpty()) {
                        throw new CompletionException(
                                new MediasoupClientException("produce handler completed with an empty id"));
                    }
                    var producer = new Producer(
                            id,
                            result.localId(),
                            result.rtpSender(),
                            options.track(),
                            kind,
                            result.rtpParameters(),
                            options.disableTrackOnPause(),
                            options.zeroRtpOnPause(),
                            options.appData(),
                            executor(),
                            new Producer.TransportListener() {
                                @Override
                                public void closed(Producer producer) {
                                    producerClosed(producer);
                                }

                                @Override
                                public void paused(Producer producer) {
                                    producerPaused(producer);
                                }

                                @Override
                                public void resumed(Producer producer) {
                                    producerResumed(producer);
                                }

                                @Override
                                public CompletableFuture<Void> replaceTrack(
                                        Producer producer, @Nullable MediaStreamTrack track) {
                                    return queue().push(
                                                    "producer @replacetrack event",
                                                    () -> handler().replaceTrack(producer.localId(), track));
                                }

                                @Override
                                public CompletableFuture<Void> setMaxSpatialLayer(Producer producer, int spatialLayer) {
                                    return queue().push(
                                                    "producer @setMaxSpatialLayer event",
                                                    () -> handler()
                                                            .setMaxSpatialLayer(producer.localId(), spatialLayer));
                                }

                                @Override
                                public CompletableFuture<Void> setRtpEncodingParameters(
                                        Producer producer, RTCRtpEncodingParameters params) {
                                    return queue().push(
                                                    "producer @setRtpEncodingParameters event",
                                                    () -> handler()
                                                            .setRtpEncodingParameters(producer.localId(), params));
                                }

                                @Override
                                public CompletableFuture<RTCStatsReport> getStats(Producer producer) {
                                    return producerStats(producer);
                                }
                            });
                    producers.put(id, producer);
                    // The transport may have closed while the application created the producer.
                    if (closed()) {
                        producers.remove(id);
                        throw new CompletionException(new InvalidStateException("closed"));
                    }

                    // Emit observer event.
                    executor().execute(() -> observer().emitNewProducer(producer));

                    return producer;
                })
                .whenComplete((producer, error) -> {
                    if (error != null) {
                        handler().stopSending(result.localId());
                    }
                });
    }

    /**
     * Sends data to mediasoup through a new data channel. The handler opens it, negotiating SCTP the
     * first time and connecting the transport first if needed, and the {@link ProduceDataHandler}
     * creates the data producer on the server.
     *
     * <p>Productions run one after another, after the other operations of the transport.
     *
     * @param options the reliability, label, protocol and application data of the data channel
     * @return a future with the data producer; it fails with an {@link InvalidStateException} if the
     *     transport is closed, before or while producing, or was created without a {@link
     *     ProduceDataHandler}, with an {@link UnsupportedException} if the server transport has no
     *     SCTP, with a {@link MediasoupClientException} if the {@link ProduceDataHandler} fails, or with
     *     the failure of the handler
     */
    public CompletableFuture<DataProducer> produceData(DataProducerOptions options) {
        requireNonNull(options, "options");
        logger.debug("produceData()");

        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        } else if (sctpParameters() == null) {
            return CompletableFuture.failedFuture(new UnsupportedException("SCTP not enabled by remote Transport"));
        }
        ProduceDataHandler produceData = produceDataHandler;
        if (produceData == null) {
            return CompletableFuture.failedFuture(
                    new InvalidStateException("no produceData handler set into this transport"));
        }

        boolean ordered = options.ordered() != null
                ? options.ordered()
                : options.maxPacketLifeTime() == null && options.maxRetransmits() == null;
        String label = options.label() != null ? options.label() : "";
        String protocol = options.protocol() != null ? options.protocol() : "";
        var channelOptions = new HandlerSendDataChannelOptions(new SctpStreamParameters(
                null, ordered, options.maxPacketLifeTime(), options.maxRetransmits(), label, protocol));

        return queue().push(
                        "transport.produceData()",
                        () -> handler()
                                .sendDataChannel(channelOptions)
                                .thenComposeAsync(
                                        result -> createDataProducer(
                                                produceData, label, protocol, options.appData(), result),
                                        executor()));
    }

    // Asks the application to create the data producer on the server, and creates the local one.
    private CompletableFuture<DataProducer> createDataProducer(
            ProduceDataHandler produceData,
            String label,
            String protocol,
            Map<String, @Nullable Object> appData,
            HandlerSendDataChannelResult result) {
        SctpStreamParameters sctpStreamParameters;
        try {
            // This will fill sctpStreamParameters's missing fields with default values.
            sctpStreamParameters = Ortc.normalizeSctpStreamParameters(result.sctpStreamParameters());
        } catch (IllegalArgumentException e) {
            result.dataChannel().close();
            return CompletableFuture.failedFuture(e);
        }
        return AsyncHandlers.<String>call(
                        "produceData",
                        () -> produceData.produceData(
                                new ProduceDataRequest(sctpStreamParameters, label, protocol, appData)))
                .thenApply(id -> {
                    if (id.isEmpty()) {
                        throw new CompletionException(
                                new MediasoupClientException("produceData handler completed with an empty id"));
                    }
                    var dataProducer = new DataProducer(
                            id,
                            result.dataChannel(),
                            sctpStreamParameters,
                            appData,
                            executor(),
                            closed -> dataProducers.remove(closed.id()));
                    dataProducers.put(id, dataProducer);
                    // The transport may have closed while the application created the data producer.
                    if (closed()) {
                        dataProducers.remove(id);
                        throw new CompletionException(new InvalidStateException("closed"));
                    }

                    // Emit observer event.
                    executor().execute(() -> observer().emitNewDataProducer(dataProducer));

                    return dataProducer;
                })
                .whenComplete((dataProducer, error) -> {
                    if (error != null) {
                        result.dataChannel().close();
                    }
                });
    }

    // Keeps the fields that the browsers accept, with active true unless it is false.
    private static List<RtpEncodingParameters> normalizeEncodings(List<RtpEncodingParameters> encodings) {
        return encodings.stream()
                .map(encoding -> RtpEncodingParameters.builder()
                        .active(!Boolean.FALSE.equals(encoding.active()))
                        .scalabilityMode(encoding.scalabilityMode())
                        .scaleResolutionDownBy(encoding.scaleResolutionDownBy())
                        .maxBitrate(encoding.maxBitrate())
                        .maxFramerate(encoding.maxFramerate())
                        .adaptivePtime(encoding.adaptivePtime())
                        .priority(encoding.priority())
                        .networkPriority(encoding.networkPriority())
                        .build())
                .toList();
    }

    private void producerClosed(Producer producer) {
        producers.remove(producer.id());

        if (closed()) {
            return;
        }

        queue().push("producer @close event", () -> handler().stopSending(producer.localId()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.warn("producer closure failed", error);
                    }
                });
    }

    private void producerPaused(Producer producer) {
        if (closed()) {
            return;
        }

        queue().push("producer @pause event", () -> handler().pauseSending(producer.localId()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.warn("producer pause failed", error);
                    }
                });
    }

    private void producerResumed(Producer producer) {
        if (closed()) {
            return;
        }

        queue().push("producer @resume event", () -> handler().resumeSending(producer.localId()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.warn("producer resume failed", error);
                    }
                });
    }

    private CompletableFuture<RTCStatsReport> producerStats(Producer producer) {
        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        }

        return handler().getSenderStats(producer.localId());
    }

    @Override
    void closeChildren() {
        for (Producer producer : producers.values()) {
            producer.transportClosed();
        }
        producers.clear();

        for (DataProducer dataProducer : dataProducers.values()) {
            dataProducer.transportClosed();
        }
        dataProducers.clear();
    }
}
