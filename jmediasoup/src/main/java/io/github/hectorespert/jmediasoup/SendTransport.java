/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerSendOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendResult;
import io.github.hectorespert.jmediasoup.internal.event.AsyncHandlers;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transport for sending media and data to mediasoup. {@link Device#createSendTransport} creates it.
 */
public final class SendTransport extends Transport {

    private static final Logger logger = LoggerFactory.getLogger(SendTransport.class);

    // The produce handler of the application.
    private final ProduceHandler produceHandler;
    // Whether we can produce audio/video based on computed extended RTP capabilities.
    private final Map<MediaKind, Boolean> canProduceByKind;
    // Map of Producers indexed by id.
    private final Map<String, Producer> producers = new ConcurrentHashMap<>();

    SendTransport(
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            Map<MediaKind, Boolean> canProduceByKind,
            ConnectHandler connectHandler,
            ProduceHandler produceHandler) {
        super(TransportDirection.SEND, options, handlerFactory, getSendExtendedRtpCapabilities, connectHandler);
        this.canProduceByKind = Map.copyOf(canProduceByKind);
        this.produceHandler = requireNonNull(produceHandler, "produce");
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
                            options.appData(),
                            executor(),
                            this::producerClosed);
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

    @Override
    void closeChildren() {
        for (Producer producer : producers.values()) {
            producer.transportClosed();
        }
        producers.clear();
    }
}
