/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.handler.DataChannel;
import io.github.hectorespert.jmediasoup.internal.event.Listeners;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A data channel that receives the messages of a mediasoup data producer through a {@link
 * RecvTransport}, which {@link RecvTransport#consumeData} creates. Mediasoup has a data consumer with
 * the same id.
 *
 * <p>Unlike mediasoup-client, it has no {@code binaryType}: each {@link DataChannelMessage} says
 * whether it is binary and gives its content as bytes, a buffer or text.
 *
 * <p>It is thread-safe. Its listeners run on the executor of its transport.
 */
public final class DataConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DataConsumer.class);

    // Id.
    private final String id;
    // Associated DataProducer Id.
    private final String dataProducerId;
    // The underlying data channel.
    private final DataChannel dataChannel;
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // SCTP stream parameters.
    private final SctpStreamParameters sctpStreamParameters;
    // App custom data.
    private final Map<String, @Nullable Object> appData;
    // The executor of the transport, where the listeners run.
    private final Executor executor;
    // Tells the transport that the data consumer closed.
    private final TransportListener transportListener;
    private final RunnableListeners transportCloseListeners = new RunnableListeners("transportclose", logger);
    private final RunnableListeners openListeners = new RunnableListeners("open", logger);
    private final Listeners<Throwable> errorListeners = new Listeners<>("error", logger);
    private final RunnableListeners closeListeners = new RunnableListeners("close", logger);
    private final Listeners<DataChannelMessage> messageListeners = new Listeners<>("message", logger);
    // Observer instance.
    private final DataConsumerObserver observer = new DataConsumerObserver(logger);

    DataConsumer(
            String id,
            String dataProducerId,
            DataChannel dataChannel,
            SctpStreamParameters sctpStreamParameters,
            Map<String, @Nullable Object> appData,
            Executor executor,
            TransportListener transportListener) {
        logger.debug("constructor()");

        this.id = id;
        this.dataProducerId = dataProducerId;
        this.dataChannel = dataChannel;
        this.sctpStreamParameters = sctpStreamParameters;
        this.appData = appData;
        this.executor = executor;
        this.transportListener = transportListener;

        handleDataChannel();
    }

    /**
     * Returns the id of the data consumer.
     *
     * @return the id of the mediasoup data consumer
     */
    public String id() {
        return id;
    }

    /**
     * Returns the id of the data producer being consumed.
     *
     * @return the id of the mediasoup data producer
     */
    public String dataProducerId() {
        return dataProducerId;
    }

    /**
     * Returns whether the data consumer is closed.
     *
     * @return whether it was closed, directly, by its data channel or by closing its transport
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Returns the SCTP stream parameters.
     *
     * @return the stream id and reliability of the data channel, which mediasoup gave
     */
    public SctpStreamParameters sctpStreamParameters() {
        return sctpStreamParameters;
    }

    /**
     * Returns the state of the data channel.
     *
     * @return the ready state
     */
    public RTCDataChannelState readyState() {
        return dataChannel.readyState();
    }

    /**
     * Returns the label of the data channel.
     *
     * @return the label, empty if none
     */
    public String label() {
        return dataChannel.label();
    }

    /**
     * Returns the sub-protocol of the data channel.
     *
     * @return the sub-protocol, empty if none
     */
    public String protocol() {
        return dataChannel.protocol();
    }

    /**
     * Returns the application data given when consuming.
     *
     * @return the data, immutable, which may contain {@code null} values
     */
    public Map<String, @Nullable Object> appData() {
        return appData;
    }

    /**
     * Returns the observer, whose events are meant for monitoring.
     *
     * @return the observer of this data consumer
     */
    public DataConsumerObserver observer() {
        return observer;
    }

    /**
     * Closes the data consumer and its data channel, and the observer emits {@code close}; after that
     * no listener is called again. The application should close the mediasoup data consumer too.
     * Calling it again does nothing.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("close()");

        dataChannel.close();

        transportListener.closed(this);

        executor.execute(() -> {
            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    /**
     * Listens for the transport of the data consumer being closed, which closes the data consumer.
     *
     * @param listener called once, instead of the data consumer being closed by {@link #close()}
     * @return the subscription that removes the listener
     */
    public Subscription onTransportClose(Runnable listener) {
        return transportCloseListeners.add(listener);
    }

    /**
     * Listens for the data channel being open, after which messages arrive. A channel on an SCTP
     * association that is already up opens at once, often before the listener is added: then the
     * listener runs right away, so that it does not miss it.
     *
     * @param listener called once, when the channel opens or, if it is already open, right away
     * @return the subscription that removes the listener
     */
    public Subscription onOpen(Runnable listener) {
        return openListeners.addOnce(
                listener, () -> !closed.get() && dataChannel.readyState() == RTCDataChannelState.OPEN, executor);
    }

    /**
     * Listens for errors of the data channel.
     *
     * @param listener called with each error
     * @return the subscription that removes the listener
     */
    public Subscription onError(java.util.function.Consumer<? super Throwable> listener) {
        return errorListeners.add(listener);
    }

    /**
     * Listens for the data channel being closed, by the remote side or by the stack, as when mediasoup
     * closes the data consumer, which closes the data consumer. It is not called by {@link #close()}
     * nor by the transport closing.
     *
     * @param listener called once
     * @return the subscription that removes the listener
     */
    public Subscription onClose(Runnable listener) {
        return closeListeners.add(listener);
    }

    /**
     * Listens for the messages that the data producer sends.
     *
     * @param listener called with each message, in the order they are received
     * @return the subscription that removes the listener
     */
    public Subscription onMessage(java.util.function.Consumer<? super DataChannelMessage> listener) {
        return messageListeners.add(listener);
    }

    /** Transport was closed. */
    void transportClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("transportClosed()");

        dataChannel.close();

        executor.execute(() -> {
            transportCloseListeners.emit();

            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    // The data channel notifies on native threads; the listeners run on the executor.
    private void handleDataChannel() {
        var dataChannelObserver = new DataChannel.Observer() {
            @Override
            public void onOpen() {
                executor.execute(() -> {
                    if (closed.get()) {
                        return;
                    }

                    logger.debug("DataChannel \"open\" event");

                    openListeners.emit();
                });
            }

            @Override
            public void onError(Throwable error) {
                executor.execute(() -> {
                    if (closed.get()) {
                        return;
                    }

                    logger.error("DataChannel \"error\" event", error);

                    errorListeners.emit(error);
                });
            }

            @Override
            public void onClose() {
                executor.execute(() -> {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }

                    logger.warn("DataChannel \"close\" event");

                    transportListener.closed(DataConsumer.this);

                    closeListeners.emit();

                    // Emit observer event.
                    observer.emitClose();

                    clearListeners();
                });
            }

            @Override
            public void onMessage(DataChannelMessage message) {
                executor.execute(() -> {
                    if (closed.get()) {
                        return;
                    }

                    messageListeners.emit(message);
                });
            }
        };
        dataChannel.setObserver(dataChannelObserver);

        // The channel may have closed before the observer was set.
        if (dataChannel.readyState() == RTCDataChannelState.CLOSED) {
            dataChannelObserver.onClose();
        }
    }

    private void clearListeners() {
        transportCloseListeners.clear();
        openListeners.clear();
        errorListeners.clear();
        closeListeners.clear();
        messageListeners.clear();
        observer.clear();
    }

    /** What the transport does when a data consumer closes. */
    interface TransportListener {

        /**
         * The data consumer was closed, by the application or by its data channel.
         *
         * @param dataConsumer the data consumer
         */
        void closed(DataConsumer dataConsumer);
    }
}
