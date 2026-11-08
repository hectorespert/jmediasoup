/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCStatsReport;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveResult;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transport for receiving media and data from mediasoup. {@link Device#createRecvTransport} creates
 * it.
 */
public final class RecvTransport extends Transport {

    private static final Logger logger = LoggerFactory.getLogger(RecvTransport.class);

    // The track id of the Consumer for RTP probation, as in mediasoup-client.
    private static final String PROBATOR_TRACK_ID = "probator";

    // RTP capabilities of the device for receiving media.
    private final RtpCapabilities recvRtpCapabilities;
    // Map of Consumers indexed by id.
    private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();
    // Map of DataConsumers indexed by id.
    private final Map<String, DataConsumer> dataConsumers = new ConcurrentHashMap<>();
    // Guards the pending consumer creations, closures, pauses and resumes.
    private final ReentrantLock lock = new ReentrantLock();
    // Consumer creation tasks awaiting to be processed.
    private final List<ConsumerCreationTask> pendingConsumerTasks = new ArrayList<>();
    // Consumer creation tasks being processed by the handler, which close() fails without waiting.
    private List<ConsumerCreationTask> consumerTasksInProgress = List.of();
    // Consumer creation in progress flag.
    private boolean consumerCreationInProgress;
    // Whether the Consumer for RTP probation has been created. Only the tasks of the queue use it.
    private volatile boolean probatorConsumerCreated;
    // Consumers pending to be closed.
    private final PendingConsumers pendingCloseConsumers = new PendingConsumers("close", Handler::stopReceiving);
    // Consumers pending to be paused.
    private final PendingConsumers pendingPauseConsumers = new PendingConsumers("pause", Handler::pauseReceiving);
    // Consumers pending to be resumed.
    private final PendingConsumers pendingResumeConsumers = new PendingConsumers("resume", Handler::resumeReceiving);

    // A consume waiting to be created in the next batch.
    private record ConsumerCreationTask(ConsumerOptions options, CompletableFuture<Consumer> result) {}

    RecvTransport(
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            RtpCapabilities recvRtpCapabilities,
            ConnectHandler connectHandler) {
        super(TransportDirection.RECV, options, handlerFactory, getSendExtendedRtpCapabilities, connectHandler);
        this.recvRtpCapabilities = requireNonNull(recvRtpCapabilities, "recvRtpCapabilities");
    }

    /**
     * Receives a track that mediasoup sends, after the application created the consumer on the
     * server. The handler negotiates the receiving, connecting the transport first if needed.
     *
     * <p>As in mediasoup-client, the consumes that arrive while another operation of the transport is
     * in progress are created together, in a single negotiation.
     *
     * @param options the parameters of the mediasoup consumer
     * @return a future with the consumer; it fails with an {@link InvalidStateException} if the
     *     transport is closed, before or while consuming, with an {@link UnsupportedException} if the
     *     device cannot receive the RTP parameters, or with the failure of the handler
     */
    public CompletableFuture<Consumer> consume(ConsumerOptions options) {
        requireNonNull(options, "options");
        logger.debug("consume()");

        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        }

        // Ensure the device can consume it.
        if (!Ortc.canReceive(options.rtpParameters(), recvRtpCapabilities)) {
            return CompletableFuture.failedFuture(new UnsupportedException("cannot consume this Producer"));
        }

        var task = new ConsumerCreationTask(options, new CompletableFuture<>());
        boolean create;
        lock.lock();
        try {
            // close() sets the flag before failing the pending tasks under the lock.
            if (closed()) {
                return CompletableFuture.failedFuture(new InvalidStateException("closed"));
            }
            // Store the Consumer creation task.
            pendingConsumerTasks.add(task);
            create = !consumerCreationInProgress;
            consumerCreationInProgress = true;
        } finally {
            lock.unlock();
        }

        // There is no Consumer creation in progress, create it now.
        if (create) {
            createPendingConsumers();
        }

        return task.result();
    }

    /**
     * Receives the messages of a mediasoup data producer through a new data channel, after the
     * application created the data consumer on the server. The handler opens it, negotiating SCTP the
     * first time and connecting the transport first if needed.
     *
     * <p>Data consumptions run one after another, after the other operations of the transport.
     *
     * @param options the parameters of the mediasoup data consumer
     * @return a future with the data consumer; it fails with an {@link InvalidStateException} if the
     *     transport is closed, before or while consuming, with an {@link UnsupportedException} if the
     *     server transport has no SCTP, with an {@link IllegalArgumentException} if the SCTP stream
     *     parameters have no stream id, or with the failure of the handler
     */
    public CompletableFuture<DataConsumer> consumeData(DataConsumerOptions options) {
        requireNonNull(options, "options");
        logger.debug("consumeData()");

        SctpParameters sctpParameters = sctpParameters();
        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        } else if (sctpParameters == null) {
            return CompletableFuture.failedFuture(new UnsupportedException("SCTP not enabled by remote Transport"));
        }

        SctpStreamParameters sctpStreamParameters;
        try {
            // This may throw.
            sctpStreamParameters = Ortc.normalizeSctpStreamParameters(options.sctpStreamParameters());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        String label = options.label() != null ? options.label() : "";
        String protocol = options.protocol() != null ? options.protocol() : "";

        return queue().push(
                        "transport.consumeData()",
                        () -> handler()
                                .receiveDataChannel(new HandlerReceiveDataChannelOptions(
                                        sctpParameters.maxSendMessageSize(), sctpStreamParameters, label, protocol)))
                .thenApply(result -> {
                    var dataConsumer = new DataConsumer(
                            options.id(),
                            options.dataProducerId(),
                            result.dataChannel(),
                            sctpStreamParameters,
                            options.appData(),
                            executor(),
                            closed -> dataConsumers.remove(closed.id()));
                    dataConsumers.put(dataConsumer.id(), dataConsumer);
                    // The transport may have closed while the handler received.
                    if (closed()) {
                        dataConsumers.remove(dataConsumer.id());
                        result.dataChannel().close();
                        throw new CompletionException(new InvalidStateException("closed"));
                    }

                    // Emit observer event.
                    executor().execute(() -> observer().emitNewDataConsumer(dataConsumer));

                    return dataConsumer;
                });
    }

    private void createPendingConsumers() {
        queue().push("transport.createPendingConsumers()", () -> {
                    List<ConsumerCreationTask> tasks;
                    lock.lock();
                    try {
                        tasks = List.copyOf(pendingConsumerTasks);
                        // Clear pending Consumer tasks.
                        pendingConsumerTasks.clear();
                        consumerTasksInProgress = tasks;
                    } finally {
                        lock.unlock();
                    }

                    if (tasks.isEmpty()) {
                        logger.debug("createPendingConsumers() | there is no Consumer to be created");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Fill options list.
                    List<HandlerReceiveOptions> optionsList = tasks.stream()
                            .map(task -> new HandlerReceiveOptions(
                                    task.options().id(),
                                    task.options().kind(),
                                    task.options().rtpParameters(),
                                    task.options().streamId()))
                            .toList();

                    CompletableFuture<List<HandlerReceiveResult>> received;
                    try {
                        received = handler().receive(optionsList);
                    } catch (RuntimeException e) {
                        received = CompletableFuture.failedFuture(e);
                    }
                    return received.handle((results, error) -> {
                                lock.lock();
                                try {
                                    consumerTasksInProgress = List.of();
                                } finally {
                                    lock.unlock();
                                }
                                if (error != null) {
                                    fail(tasks, unwrap(error));
                                    return null;
                                }
                                return createConsumers(tasks, results);
                            })
                            // If RTP probation must be handled, do it now.
                            .thenCompose(this::createProbatorConsumer);
                })
                .whenComplete((ignored, error) -> {
                    boolean again;
                    lock.lock();
                    try {
                        // There are pending Consumer tasks, enqueue their creation.
                        again = !closed() && !pendingConsumerTasks.isEmpty();
                        consumerCreationInProgress = again;
                    } finally {
                        lock.unlock();
                    }
                    if (again) {
                        createPendingConsumers();
                    }
                });
    }

    // Creates the consumers of a batch, and returns the first video one if the Consumer for RTP
    // probation has to be created with it.
    private @Nullable Consumer createConsumers(List<ConsumerCreationTask> tasks, List<HandlerReceiveResult> results) {
        if (results.size() != tasks.size()) {
            fail(
                    tasks,
                    new MediasoupClientException(
                            "handler returned " + results.size() + " results for " + tasks.size() + " receivings"));
            return null;
        }
        // Video Consumer in order to create the probator.
        Consumer videoConsumerForProbator = null;
        for (int idx = 0; idx < tasks.size(); ++idx) {
            ConsumerOptions options = tasks.get(idx).options();
            HandlerReceiveResult result = results.get(idx);
            var consumer = new Consumer(
                    options.id(),
                    result.localId(),
                    options.producerId(),
                    result.rtpReceiver(),
                    result.track(),
                    options.kind(),
                    options.rtpParameters(),
                    options.appData(),
                    executor(),
                    new Consumer.TransportListener() {
                        @Override
                        public void closed(Consumer consumer) {
                            consumerClosed(consumer);
                        }

                        @Override
                        public void paused(Consumer consumer) {
                            consumerPaused(consumer);
                        }

                        @Override
                        public void resumed(Consumer consumer) {
                            consumerResumed(consumer);
                        }

                        @Override
                        public CompletableFuture<RTCStatsReport> getStats(Consumer consumer) {
                            return consumerStats(consumer);
                        }
                    });

            consumers.put(consumer.id(), consumer);
            // The transport may have closed while the handler received.
            if (closed()) {
                consumers.remove(consumer.id());
                tasks.get(idx).result().completeExceptionally(new InvalidStateException("closed"));
                continue;
            }

            // If this is the first video Consumer and the Consumer for RTP probation
            // has not yet been created, it's time to create it.
            if (!probatorConsumerCreated && videoConsumerForProbator == null && options.kind() == MediaKind.VIDEO) {
                videoConsumerForProbator = consumer;
            }

            // Emit observer event.
            executor().execute(() -> observer().emitNewConsumer(consumer));

            tasks.get(idx).result().complete(consumer);
        }
        return videoConsumerForProbator;
    }

    // Receives the RTP that mediasoup sends to probe the bandwidth, with the parameters of the first
    // video consumer. A failure is only logged, and the next video consumer tries again.
    private CompletableFuture<@Nullable Void> createProbatorConsumer(@Nullable Consumer videoConsumerForProbator) {
        if (videoConsumerForProbator == null || closed()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<List<HandlerReceiveResult>> received;
        try {
            RtpParameters probatorRtpParameters =
                    Ortc.generateProbatorRtpParameters(videoConsumerForProbator.rtpParameters());
            received = handler()
                    .receive(List.of(new HandlerReceiveOptions(
                            PROBATOR_TRACK_ID, MediaKind.VIDEO, probatorRtpParameters, null)));
        } catch (RuntimeException e) {
            received = CompletableFuture.failedFuture(e);
        }
        return received.handle((results, error) -> {
            if (error != null) {
                logger.error("createPendingConsumers() | failed to create Consumer for RTP probation", unwrap(error));
            } else {
                logger.debug("createPendingConsumers() | Consumer for RTP probation created");
                probatorConsumerCreated = true;
            }
            return null;
        });
    }

    private void consumerClosed(Consumer consumer) {
        consumers.remove(consumer.id());
        lock.lock();
        try {
            pendingPauseConsumers.remove(consumer);
            pendingResumeConsumers.remove(consumer);
        } finally {
            lock.unlock();
        }

        if (closed()) {
            return;
        }

        // Store the Consumer into the close list.
        pendingCloseConsumers.add(consumer);
    }

    private void consumerPaused(Consumer consumer) {
        if (closed()) {
            return;
        }

        lock.lock();
        try {
            // If Consumer is pending to be resumed, remove from pending resume list.
            pendingResumeConsumers.remove(consumer);
        } finally {
            lock.unlock();
        }

        // Store the Consumer into the pending list.
        pendingPauseConsumers.add(consumer);
    }

    private void consumerResumed(Consumer consumer) {
        if (closed()) {
            return;
        }

        lock.lock();
        try {
            // If Consumer is pending to be paused, remove from pending pause list.
            pendingPauseConsumers.remove(consumer);
        } finally {
            lock.unlock();
        }

        // Store the Consumer into the pending list.
        pendingResumeConsumers.add(consumer);
    }

    /**
     * Consumers waiting to be closed, paused or resumed by the handler, all in one call, as the
     * closePendingConsumers(), pausePendingConsumers() and resumePendingConsumers() of mediasoup-client.
     * The lock of the transport guards it.
     */
    private final class PendingConsumers {

        private final String action;
        private final BiFunction<Handler, List<String>, CompletableFuture<Void>> handlerCall;
        private final Map<String, Consumer> consumers = new LinkedHashMap<>();
        // Handler call in progress flag.
        private boolean inProgress;

        PendingConsumers(String action, BiFunction<Handler, List<String>, CompletableFuture<Void>> handlerCall) {
            this.action = action;
            this.handlerCall = handlerCall;
        }

        void add(Consumer consumer) {
            boolean process;
            lock.lock();
            try {
                consumers.put(consumer.id(), consumer);
                process = !inProgress;
                inProgress = true;
            } finally {
                lock.unlock();
            }

            // There is no handler call in progress, do it now.
            if (process) {
                process();
            }
        }

        // Called with the lock held.
        void remove(Consumer consumer) {
            consumers.remove(consumer.id());
        }

        // Called with the lock held.
        void clear() {
            consumers.clear();
        }

        private void process() {
            String method = action + "PendingConsumers()";
            queue().push("transport." + method, () -> {
                        List<String> localIds;
                        lock.lock();
                        try {
                            localIds = consumers.values().stream()
                                    .map(Consumer::localId)
                                    .toList();
                            // Clear pending Consumer map.
                            consumers.clear();
                        } finally {
                            lock.unlock();
                        }

                        if (localIds.isEmpty()) {
                            logger.debug("{} | there is no Consumer to {}", method, action);
                            return CompletableFuture.completedFuture(null);
                        }

                        CompletableFuture<Void> done;
                        try {
                            done = handlerCall.apply(handler(), localIds);
                        } catch (RuntimeException e) {
                            done = CompletableFuture.failedFuture(e);
                        }
                        return done.exceptionally(error -> {
                            logger.error("{} | failed to {} Consumers", method, action, unwrap(error));
                            return null;
                        });
                    })
                    .whenComplete((ignored, error) -> {
                        boolean again;
                        lock.lock();
                        try {
                            // There are pending Consumers, do it again.
                            again = !closed() && !consumers.isEmpty();
                            inProgress = again;
                        } finally {
                            lock.unlock();
                        }
                        if (again) {
                            process();
                        }
                    });
        }
    }

    private CompletableFuture<RTCStatsReport> consumerStats(Consumer consumer) {
        if (closed()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        }

        return handler().getReceiverStats(consumer.localId());
    }

    @Override
    void closeChildren() {
        List<ConsumerCreationTask> tasks = new ArrayList<>();
        lock.lock();
        try {
            tasks.addAll(consumerTasksInProgress);
            tasks.addAll(pendingConsumerTasks);
            pendingConsumerTasks.clear();
            pendingCloseConsumers.clear();
            pendingPauseConsumers.clear();
            pendingResumeConsumers.clear();
        } finally {
            lock.unlock();
        }
        fail(tasks, new InvalidStateException("closed"));

        for (Consumer consumer : consumers.values()) {
            consumer.transportClosed();
        }
        consumers.clear();

        for (DataConsumer dataConsumer : dataConsumers.values()) {
            dataConsumer.transportClosed();
        }
        dataConsumers.clear();
    }

    private static void fail(List<ConsumerCreationTask> tasks, Throwable error) {
        for (ConsumerCreationTask task : tasks) {
            task.result().completeExceptionally(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
