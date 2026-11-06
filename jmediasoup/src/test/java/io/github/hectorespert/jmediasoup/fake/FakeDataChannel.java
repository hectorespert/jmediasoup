/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.handler.DataChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A {@link DataChannel} without WebRTC, for tests with {@code FakeHandler}. It starts connecting;
 * the test drives it with {@link #open()}, {@link #receive}, {@link #fail} and
 * {@link #setBufferedAmount}, and reads what was sent with {@link #sentMessages()}.
 */
public final class FakeDataChannel implements DataChannel {

    private final String label;
    private final String protocol;
    private final List<DataChannelMessage> sent = new CopyOnWriteArrayList<>();
    private volatile RTCDataChannelState state = RTCDataChannelState.CONNECTING;
    private volatile long bufferedAmount;
    private volatile long bufferedAmountLowThreshold;
    private volatile @Nullable Observer observer;

    /**
     * @param label the label
     * @param protocol the sub-protocol, empty if none
     */
    public FakeDataChannel(String label, String protocol) {
        this.label = label;
        this.protocol = protocol;
    }

    /** Opens the channel and notifies the observer. */
    public void open() {
        state = RTCDataChannelState.OPEN;
        notify(Observer::onOpen);
    }

    /** Delivers a message from the remote side. */
    public void receive(DataChannelMessage message) {
        notify(observer -> observer.onMessage(message));
    }

    /** Reports a failure, as the native channel does before closing. */
    public void fail(Throwable error) {
        notify(observer -> observer.onError(error));
    }

    /** Changes the buffered amount, notifying the observer when it falls to the threshold. */
    public void setBufferedAmount(long amount) {
        long previous = bufferedAmount;
        bufferedAmount = amount;
        if (previous > bufferedAmountLowThreshold && amount <= bufferedAmountLowThreshold) {
            notify(Observer::onBufferedAmountLow);
        }
    }

    /** Returns the messages sent so far, in order. */
    public List<DataChannelMessage> sentMessages() {
        return List.copyOf(sent);
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
        return bufferedAmount;
    }

    @Override
    public long bufferedAmountLowThreshold() {
        return bufferedAmountLowThreshold;
    }

    @Override
    public void setBufferedAmountLowThreshold(long threshold) {
        bufferedAmountLowThreshold = threshold;
    }

    @Override
    public void send(DataChannelMessage message) throws InvalidStateException {
        if (state != RTCDataChannelState.OPEN) {
            throw new InvalidStateException("data channel is " + state);
        }
        sent.add(message);
    }

    @Override
    public void close() {
        if (state == RTCDataChannelState.CLOSED) {
            return;
        }
        state = RTCDataChannelState.CLOSED;
        notify(Observer::onClose);
    }

    @Override
    public void setObserver(@Nullable Observer observer) {
        this.observer = observer;
    }

    private void notify(Consumer<Observer> event) {
        Observer current = observer;
        if (current != null) {
            event.accept(current);
        }
    }
}
