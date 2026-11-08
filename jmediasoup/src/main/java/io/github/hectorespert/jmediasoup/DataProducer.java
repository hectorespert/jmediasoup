/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

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
 * A data channel that sends messages to mediasoup through a {@link SendTransport}, which {@link
 * SendTransport#produceData} creates. Mediasoup has a data producer with the same id.
 *
 * <p>It is thread-safe. Its listeners run on the executor of its transport.
 */
public final class DataProducer {

    private static final Logger logger = LoggerFactory.getLogger(DataProducer.class);

    // Id.
    private final String id;
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
    // Tells the transport that the data producer closed.
    private final TransportListener transportListener;
    private final RunnableListeners transportCloseListeners = new RunnableListeners("transportclose", logger);
    private final RunnableListeners openListeners = new RunnableListeners("open", logger);
    private final Listeners<Throwable> errorListeners = new Listeners<>("error", logger);
    private final RunnableListeners closeListeners = new RunnableListeners("close", logger);
    private final RunnableListeners bufferedAmountLowListeners = new RunnableListeners("bufferedamountlow", logger);
    // Observer instance.
    private final DataProducerObserver observer = new DataProducerObserver(logger);

    DataProducer(
            String id,
            DataChannel dataChannel,
            SctpStreamParameters sctpStreamParameters,
            Map<String, @Nullable Object> appData,
            Executor executor,
            TransportListener transportListener) {
        logger.debug("constructor()");

        this.id = id;
        this.dataChannel = dataChannel;
        this.sctpStreamParameters = sctpStreamParameters;
        this.appData = appData;
        this.executor = executor;
        this.transportListener = transportListener;

        handleDataChannel();
    }

    /**
     * Returns the id of the data producer.
     *
     * @return the id of the mediasoup data producer
     */
    public String id() {
        return id;
    }

    /**
     * Returns whether the data producer is closed.
     *
     * @return whether it was closed, directly, by its data channel or by closing its transport
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Returns the SCTP stream parameters.
     *
     * @return the stream id and reliability of the data channel
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
     * Returns the bytes queued in the data channel and not sent yet.
     *
     * @return the buffered amount, in bytes
     */
    public long bufferedAmount() {
        return dataChannel.bufferedAmount();
    }

    /**
     * Returns the buffered amount at or below which {@link #onBufferedAmountLow} fires.
     *
     * @return the threshold, in bytes
     */
    public long bufferedAmountLowThreshold() {
        return dataChannel.bufferedAmountLowThreshold();
    }

    /**
     * Sets the buffered amount at or below which {@link #onBufferedAmountLow} fires.
     *
     * @param threshold the threshold, in bytes
     */
    public void setBufferedAmountLowThreshold(long threshold) {
        dataChannel.setBufferedAmountLowThreshold(threshold);
    }

    /**
     * Returns the application data given when producing.
     *
     * @return the data, immutable, which may contain {@code null} values
     */
    public Map<String, @Nullable Object> appData() {
        return appData;
    }

    /**
     * Returns the observer, whose events are meant for monitoring.
     *
     * @return the observer of this data producer
     */
    public DataProducerObserver observer() {
        return observer;
    }

    /**
     * Sends a text message.
     *
     * @param text the message
     * @throws InvalidStateException if the data producer is closed
     * @throws MediasoupClientException if the data channel cannot send it, as when it is not open
     */
    public void send(String text) throws MediasoupClientException {
        send(DataChannelMessage.text(text));
    }

    /**
     * Sends a binary message.
     *
     * @param bytes the message, copied before sending
     * @throws InvalidStateException if the data producer is closed
     * @throws MediasoupClientException if the data channel cannot send it, as when it is not open
     */
    public void send(byte[] bytes) throws MediasoupClientException {
        send(DataChannelMessage.binary(bytes));
    }

    /**
     * Sends a message, as text or binary.
     *
     * @param message the message
     * @throws InvalidStateException if the data producer is closed
     * @throws MediasoupClientException if the data channel cannot send it, as when it is not open
     */
    public void send(DataChannelMessage message) throws MediasoupClientException {
        requireNonNull(message, "message");
        logger.debug("send()");

        if (closed.get()) {
            throw new InvalidStateException("closed");
        }

        dataChannel.send(message);
    }

    /**
     * Closes the data producer and its data channel, and the observer emits {@code close}; after that
     * no listener is called again. The application should close the mediasoup data producer too.
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
     * Listens for the transport of the data producer being closed, which closes the data producer.
     *
     * @param listener called once, instead of the data producer being closed by {@link #close()}
     * @return the subscription that removes the listener
     */
    public Subscription onTransportClose(Runnable listener) {
        return transportCloseListeners.add(listener);
    }

    /**
     * Listens for the data channel being open, after which it can send. A channel on an SCTP
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
     * Listens for the data channel being closed, by the remote side or by the stack, which closes the
     * data producer. It is not called by {@link #close()} nor by the transport closing.
     *
     * @param listener called once
     * @return the subscription that removes the listener
     */
    public Subscription onClose(Runnable listener) {
        return closeListeners.add(listener);
    }

    /**
     * Listens for the buffered amount falling to {@link #bufferedAmountLowThreshold()}, to send more.
     *
     * @param listener called each time it falls to the threshold
     * @return the subscription that removes the listener
     */
    public Subscription onBufferedAmountLow(Runnable listener) {
        return bufferedAmountLowListeners.add(listener);
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

                    transportListener.closed(DataProducer.this);

                    closeListeners.emit();

                    // Emit observer event.
                    observer.emitClose();

                    clearListeners();
                });
            }

            @Override
            public void onMessage(DataChannelMessage message) {
                if (closed.get()) {
                    return;
                }

                logger.warn("DataChannel \"message\" event in a DataProducer, message discarded");
            }

            @Override
            public void onBufferedAmountLow() {
                executor.execute(() -> {
                    if (closed.get()) {
                        return;
                    }

                    bufferedAmountLowListeners.emit();
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
        bufferedAmountLowListeners.clear();
        observer.clear();
    }

    /** What the transport does when a data producer closes. */
    interface TransportListener {

        /**
         * The data producer was closed, by the application or by its data channel.
         *
         * @param dataProducer the data producer
         */
        void closed(DataProducer dataProducer);
    }
}
