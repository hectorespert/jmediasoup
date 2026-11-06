/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerListener;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.internal.event.AsyncHandlers;
import io.github.hectorespert.jmediasoup.internal.event.Listeners;
import io.github.hectorespert.jmediasoup.internal.event.OperationQueue;
import io.github.hectorespert.jmediasoup.internal.event.SerialExecutor;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection with a mediasoup transport, through which media and data are sent ({@link
 * SendTransport}) or received ({@link RecvTransport}). A loaded {@link Device} creates it.
 *
 * <p>It is thread-safe. Each transport has its own executor, where every listener and handler of the
 * application runs: one at a time, in the order of the events, and never on the thread that called a
 * method of the transport nor on a native thread of the WebRTC stack. They may block, which only
 * delays the next events of this transport. They do not inherit thread-local values, such as the
 * SLF4J {@code MDC}. The operations of a transport run one after another, as in mediasoup-client.
 */
public abstract sealed class Transport permits SendTransport, RecvTransport {

    private static final Logger logger = LoggerFactory.getLogger(Transport.class);

    // Id.
    private final String id;
    // Direction.
    private final TransportDirection direction;
    // App custom data.
    private final Map<String, @Nullable Object> appData;
    // The connect handler of the application.
    private final ConnectHandler connectHandler;
    // Where the listeners and handlers of the application run, one at a time.
    private final SerialExecutor executor = new SerialExecutor();
    // Makes the operations happen sequentially.
    private final OperationQueue queue = new OperationQueue(executor);
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Transport connection state.
    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.NEW);
    // ICE gathering state.
    private final AtomicReference<IceGatheringState> iceGatheringState = new AtomicReference<>(IceGatheringState.NEW);
    private final Listeners<ConnectionState> connectionStateListeners =
            new Listeners<>("connectionstatechange", logger);
    private final Listeners<IceGatheringState> iceGatheringStateListeners =
            new Listeners<>("icegatheringstatechange", logger);
    private final Listeners<RTCPeerConnectionIceErrorEvent> iceCandidateErrorListeners =
            new Listeners<>("icecandidateerror", logger);
    // Observer instance.
    private final TransportObserver observer = new TransportObserver(logger);
    // RTC handler instance.
    private final Handler handler;

    Transport(
            TransportDirection direction,
            TransportOptions options,
            HandlerFactory handlerFactory,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            ConnectHandler connectHandler) {
        logger.debug("constructor() [id:{}, direction:{}]", options.id(), direction);

        this.id = options.id();
        this.direction = direction;
        this.appData = options.appData();
        this.connectHandler = requireNonNull(connectHandler, "connect");
        this.handler = requireNonNull(
                handlerFactory.create(new HandlerOptions(
                        direction,
                        options.iceParameters(),
                        options.iceCandidates(),
                        options.dtlsParameters(),
                        adaptSctpParameters(options.sctpParameters()),
                        options.iceServers(),
                        options.iceTransportPolicy(),
                        getSendExtendedRtpCapabilities,
                        new HandlerEvents())),
                "handlerFactory.create()");
    }

    // Fills the message sizes that old mediasoup server versions do not send.
    private static @Nullable SctpParameters adaptSctpParameters(@Nullable SctpParameters sctpParameters) {
        if (sctpParameters == null) {
            return null;
        }
        return new SctpParameters(
                sctpParameters.port(),
                sctpParameters.os(),
                sctpParameters.mis(),
                sctpParameters.maxMessageSize(),
                sctpParameters.maxSendMessageSize() != null
                        ? sctpParameters.maxSendMessageSize()
                        : sctpParameters.maxMessageSize(),
                sctpParameters.maxReceiveMessageSize() != null
                        ? sctpParameters.maxReceiveMessageSize()
                        : sctpParameters.maxMessageSize());
    }

    /**
     * Returns the id of the transport.
     *
     * @return the id of the mediasoup transport
     */
    public String id() {
        return id;
    }

    /**
     * Returns whether the transport is closed.
     *
     * @return whether {@link #close()} was called
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Returns the direction of the transport.
     *
     * @return {@link TransportDirection#SEND} or {@link TransportDirection#RECV}
     */
    public TransportDirection direction() {
        return direction;
    }

    /**
     * Returns the handler of the WebRTC stack that the transport uses.
     *
     * @return the handler
     */
    public Handler handler() {
        return handler;
    }

    /**
     * Returns the connection state, as of the last event delivered to the listeners.
     *
     * @return the connection state; {@link ConnectionState#CLOSED} once the transport is closed
     */
    public ConnectionState connectionState() {
        return connectionState.get();
    }

    /**
     * Returns the ICE gathering state, as of the last event delivered to the listeners.
     *
     * @return the ICE gathering state
     */
    public IceGatheringState iceGatheringState() {
        return iceGatheringState.get();
    }

    /**
     * Returns the application data given when creating the transport.
     *
     * @return the data, immutable, which may contain {@code null} values
     */
    public Map<String, @Nullable Object> appData() {
        return appData;
    }

    /**
     * Returns the observer, whose events are meant for monitoring.
     *
     * @return the observer of this transport
     */
    public TransportObserver observer() {
        return observer;
    }

    /**
     * Closes the transport and its handler. The operations in progress fail with an {@link
     * InvalidStateException}, the connection state becomes {@link ConnectionState#CLOSED} without an
     * event, and the observer emits {@code close}; after that no listener is called again. Calling it
     * again does nothing.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("close()");

        // Stop the operation queue.
        queue.stop();

        // Close the handler.
        try {
            handler.close();
        } catch (RuntimeException e) {
            logger.error("Handler failed to close", e);
        }

        // Change connection state to 'closed' since the handler may not report it.
        connectionState.set(ConnectionState.CLOSED);

        executor.execute(() -> {
            // Emit observer event.
            observer.emitClose();

            connectionStateListeners.clear();
            iceGatheringStateListeners.clear();
            iceCandidateErrorListeners.clear();
            observer.clear();
        });
    }

    /**
     * Listens for changes in the connection state.
     *
     * @param listener called with the new state; not called once the transport is closed
     * @return the subscription that removes the listener
     */
    public Subscription onConnectionStateChange(Consumer<? super ConnectionState> listener) {
        return connectionStateListeners.add(listener);
    }

    /**
     * Listens for changes in the ICE gathering state.
     *
     * @param listener called with the new state; not called once the transport is closed
     * @return the subscription that removes the listener
     */
    public Subscription onIceGatheringStateChange(Consumer<? super IceGatheringState> listener) {
        return iceGatheringStateListeners.add(listener);
    }

    /**
     * Listens for errors while gathering ICE candidates, such as a TURN server that does not answer.
     *
     * @param listener called with each error; not called once the transport is closed
     * @return the subscription that removes the listener
     */
    public Subscription onIceCandidateError(Consumer<? super RTCPeerConnectionIceErrorEvent> listener) {
        return iceCandidateErrorListeners.add(listener);
    }

    /** Returns the executor where the listeners and handlers of the application run. */
    final Executor executor() {
        return executor;
    }

    /** Returns the queue of the operations of the transport. */
    final OperationQueue queue() {
        return queue;
    }

    // Receives the events of the handler, possibly on a native thread, and hands them to the executor.
    private final class HandlerEvents implements HandlerListener {

        @Override
        public CompletionStage<Void> connect(DtlsParameters dtlsParameters) {
            requireNonNull(dtlsParameters, "dtlsParameters");
            var result = new CompletableFuture<Void>();
            executor.execute(() -> {
                if (closed.get()) {
                    result.completeExceptionally(new InvalidStateException("closed"));
                    return;
                }
                AsyncHandlers.run("connect", () -> connectHandler.connect(dtlsParameters))
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                result.completeExceptionally(error);
                            } else {
                                result.complete(null);
                            }
                        });
            });
            return result;
        }

        @Override
        public void connectionStateChange(ConnectionState state) {
            requireNonNull(state, "connectionState");
            executor.execute(() -> {
                // Once closed, the state stays closed.
                ConnectionState previous = connectionState.getAndUpdate(current -> closed.get() ? current : state);
                if (previous == state || closed.get()) {
                    return;
                }
                logger.debug("connection state changed to {}", state);
                connectionStateListeners.emit(state);
            });
        }

        @Override
        public void iceGatheringStateChange(IceGatheringState state) {
            requireNonNull(state, "iceGatheringState");
            executor.execute(() -> {
                if (iceGatheringState.getAndSet(state) == state) {
                    return;
                }
                logger.debug("ICE gathering state changed to {}", state);
                if (!closed.get()) {
                    iceGatheringStateListeners.emit(state);
                }
            });
        }

        @Override
        public void iceCandidateError(RTCPeerConnectionIceErrorEvent event) {
            requireNonNull(event, "event");
            executor.execute(() -> {
                logger.debug(
                        "ICE candidate error [url:{}, localAddress:{}, localPort:{}]: {} \"{}\"",
                        event.getUrl(),
                        event.getAddress(),
                        event.getPort(),
                        event.getErrorCode(),
                        event.getErrorText());
                if (!closed.get()) {
                    iceCandidateErrorListeners.emit(event);
                }
            });
        }
    }
}
