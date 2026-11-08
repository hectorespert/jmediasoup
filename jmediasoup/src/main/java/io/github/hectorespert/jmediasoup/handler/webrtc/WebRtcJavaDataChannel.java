/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.handler.DataChannel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link DataChannel} over a webrtc-java {@link RTCDataChannel}.
 *
 * <p>webrtc-java reports the events of the channel on the signalling thread of its factory, where the
 * observer must not block. It has no {@code bufferedamountlow} event, so this class computes it from
 * the changes of the buffered amount, with the threshold that it keeps. The handler releases the
 * native object when it closes, after which the getters return the last values.
 */
final class WebRtcJavaDataChannel implements DataChannel {

    private static final Logger logger = LoggerFactory.getLogger(WebRtcJavaDataChannel.class);

    private final RTCDataChannel channel;
    private final String label;
    private final String protocol;
    // Guards the native object, which cannot be used once disposed. The observer does not take it,
    // so that dispose() never waits for the signalling thread while holding it.
    private final Object lock = new Object();
    private volatile boolean disposed;
    private volatile RTCDataChannelState state;
    private volatile long bufferedAmountLowThreshold;
    private volatile @Nullable Observer observer;

    WebRtcJavaDataChannel(RTCDataChannel channel) {
        this.channel = channel;
        this.label = channel.getLabel();
        this.protocol = channel.getProtocol();
        this.state = channel.getState();
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                long threshold = bufferedAmountLowThreshold;
                if (disposed || previousAmount <= threshold) {
                    return;
                }
                if (channel.getBufferedAmount() <= threshold) {
                    notifyObserver(Observer::onBufferedAmountLow);
                }
            }

            @Override
            public void onStateChange() {
                if (disposed) {
                    return;
                }
                RTCDataChannelState newState = channel.getState();
                if (state == newState) {
                    return;
                }
                state = newState;
                switch (newState) {
                    case OPEN -> notifyObserver(Observer::onOpen);
                    case CLOSED -> notifyObserver(Observer::onClose);
                    default -> {}
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                // The buffer is only valid during the call.
                DataChannelMessage message = DataChannelMessage.of(buffer.data, buffer.binary);
                notifyObserver(current -> current.onMessage(message));
            }
        });
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String protocol() {
        return protocol;
    }

    @Override
    public RTCDataChannelState readyState() {
        return state;
    }

    @Override
    public long bufferedAmount() {
        synchronized (lock) {
            return disposed ? 0 : channel.getBufferedAmount();
        }
    }

    @Override
    public long bufferedAmountLowThreshold() {
        return bufferedAmountLowThreshold;
    }

    @Override
    public void setBufferedAmountLowThreshold(long threshold) {
        bufferedAmountLowThreshold = threshold;
    }

    /**
     * {@inheritDoc}
     *
     * @throws InvalidStateException if the channel is not open
     * @throws MediasoupClientException if webrtc-java fails to send it
     */
    @Override
    public void send(DataChannelMessage message) throws MediasoupClientException {
        requireNonNull(message, "message");
        synchronized (lock) {
            RTCDataChannelState current = disposed ? RTCDataChannelState.CLOSED : channel.getState();
            if (current != RTCDataChannelState.OPEN) {
                throw new InvalidStateException("data channel is " + current);
            }
            try {
                channel.send(new RTCDataChannelBuffer(message.asByteBuffer(), message.isBinary()));
            } catch (Exception e) {
                throw new MediasoupClientException("failed to send the message", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            channel.close();
        }
    }

    @Override
    public void setObserver(@Nullable Observer observer) {
        this.observer = observer;
    }

    /** Releases the native channel, when its handler closes. */
    void dispose() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
            state = RTCDataChannelState.CLOSED;
            try {
                channel.unregisterObserver();
                channel.dispose();
            } catch (RuntimeException e) {
                logger.warn("dispose() | failed to release the data channel", e);
            }
        }
    }

    private void notifyObserver(java.util.function.Consumer<Observer> event) {
        Observer current = observer;
        if (current == null) {
            return;
        }
        try {
            event.accept(current);
        } catch (RuntimeException e) {
            logger.error("data channel observer threw an exception", e);
        }
    }
}
