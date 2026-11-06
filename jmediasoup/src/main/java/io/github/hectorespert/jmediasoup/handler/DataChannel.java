/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import org.jspecify.annotations.Nullable;

/**
 * A data channel as the handler gives it to a data producer or data consumer. It abstracts the
 * webrtc-java {@code RTCDataChannel}, which cannot be created without the natives, so that a handler
 * without WebRTC can provide its own.
 *
 * <p>The observer may be called on any thread; data producers and consumers move the events to
 * their transport queue.
 */
public interface DataChannel {

    /**
     * Returns the label.
     *
     * @return the label given when the channel was created
     */
    String label();

    /**
     * Returns the sub-protocol.
     *
     * @return the sub-protocol, empty if none
     */
    String protocol();

    /**
     * Returns the state.
     *
     * @return the current state
     */
    RTCDataChannelState readyState();

    /**
     * Returns the bytes queued to send.
     *
     * @return the number of bytes not yet sent
     */
    long bufferedAmount();

    /**
     * Returns the threshold of {@link Observer#onBufferedAmountLow()}.
     *
     * @return the threshold, in bytes; 0 by default
     */
    long bufferedAmountLowThreshold();

    /**
     * Sets the threshold of {@link Observer#onBufferedAmountLow()}.
     *
     * @param threshold the threshold, in bytes
     */
    void setBufferedAmountLowThreshold(long threshold);

    /**
     * Sends a message.
     *
     * @param message the message
     * @throws MediasoupClientException if the channel is not open or the message cannot be sent
     */
    void send(DataChannelMessage message) throws MediasoupClientException;

    /** Closes the channel. Closing a closed channel does nothing. */
    void close();

    /**
     * Sets the observer of the channel events, replacing the previous one.
     *
     * @param observer the observer, or {@code null} to stop observing
     */
    void setObserver(@Nullable Observer observer);

    /** Receives the events of a data channel. Every method does nothing by default. */
    interface Observer {

        /** The channel is open. */
        default void onOpen() {}

        /** The channel is closed. */
        default void onClose() {}

        /**
         * The channel failed.
         *
         * @param error the failure
         */
        default void onError(Throwable error) {}

        /**
         * A message arrived.
         *
         * @param message the message
         */
        default void onMessage(DataChannelMessage message) {}

        /** The buffered amount fell to the threshold or below it. */
        default void onBufferedAmountLow() {}
    }
}
