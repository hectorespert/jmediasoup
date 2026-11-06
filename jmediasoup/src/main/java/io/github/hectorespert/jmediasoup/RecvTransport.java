/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveResult;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
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
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transport for receiving media and data from mediasoup. {@link Device#createRecvTransport} creates
 * it.
 */
public final class RecvTransport extends Transport {

    private static final Logger logger = LoggerFactory.getLogger(RecvTransport.class);

    // RTP capabilities of the device for receiving media.
    private final RtpCapabilities recvRtpCapabilities;
    // Map of Consumers indexed by id.
    private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();
    // Guards the pending consumer creations and closures.
    private final ReentrantLock lock = new ReentrantLock();
    // Consumer creation tasks awaiting to be processed.
    private final List<ConsumerCreationTask> pendingConsumerTasks = new ArrayList<>();
    // Consumer creation tasks being processed by the handler, which close() fails without waiting.
    private List<ConsumerCreationTask> consumerTasksInProgress = List.of();
    // Consumer creation in progress flag.
    private boolean consumerCreationInProgress;
    // Consumers pending to be closed.
    private final Map<String, Consumer> pendingCloseConsumers = new LinkedHashMap<>();
    // Consumer close in progress flag.
    private boolean consumerCloseInProgress;

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
                        } else {
                            createConsumers(tasks, results);
                        }
                        return null;
                    });
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

    private void createConsumers(List<ConsumerCreationTask> tasks, List<HandlerReceiveResult> results) {
        if (results.size() != tasks.size()) {
            fail(
                    tasks,
                    new MediasoupClientException(
                            "handler returned " + results.size() + " results for " + tasks.size() + " receivings"));
            return;
        }
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
                    this::consumerClosed);

            consumers.put(consumer.id(), consumer);
            // The transport may have closed while the handler received.
            if (closed()) {
                consumers.remove(consumer.id());
                tasks.get(idx).result().completeExceptionally(new InvalidStateException("closed"));
                continue;
            }

            // Emit observer event.
            executor().execute(() -> observer().emitNewConsumer(consumer));

            tasks.get(idx).result().complete(consumer);
        }
    }

    private void consumerClosed(Consumer consumer) {
        consumers.remove(consumer.id());

        if (closed()) {
            return;
        }

        boolean close;
        lock.lock();
        try {
            // Store the Consumer into the close list.
            pendingCloseConsumers.put(consumer.id(), consumer);
            close = !consumerCloseInProgress;
            consumerCloseInProgress = true;
        } finally {
            lock.unlock();
        }

        // There is no Consumer close in progress, do it now.
        if (close) {
            closePendingConsumers();
        }
    }

    private void closePendingConsumers() {
        queue().push("transport.closePendingConsumers()", () -> {
                    List<String> localIds;
                    lock.lock();
                    try {
                        localIds = pendingCloseConsumers.values().stream()
                                .map(Consumer::localId)
                                .toList();
                        // Clear pending close Consumer map.
                        pendingCloseConsumers.clear();
                    } finally {
                        lock.unlock();
                    }

                    if (localIds.isEmpty()) {
                        logger.debug("closePendingConsumers() | there is no Consumer to be closed");
                        return CompletableFuture.completedFuture(null);
                    }

                    CompletableFuture<Void> stopped;
                    try {
                        stopped = handler().stopReceiving(localIds);
                    } catch (RuntimeException e) {
                        stopped = CompletableFuture.failedFuture(e);
                    }
                    return stopped.exceptionally(error -> {
                        logger.error("closePendingConsumers() | failed to close Consumers", unwrap(error));
                        return null;
                    });
                })
                .whenComplete((ignored, error) -> {
                    boolean again;
                    lock.lock();
                    try {
                        // There are pending Consumers to be closed, do it.
                        again = !closed() && !pendingCloseConsumers.isEmpty();
                        consumerCloseInProgress = again;
                    } finally {
                        lock.unlock();
                    }
                    if (again) {
                        closePendingConsumers();
                    }
                });
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
        } finally {
            lock.unlock();
        }
        fail(tasks, new InvalidStateException("closed"));

        for (Consumer consumer : consumers.values()) {
            consumer.transportClosed();
        }
        consumers.clear();
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
