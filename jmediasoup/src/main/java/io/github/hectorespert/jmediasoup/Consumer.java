/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A track received from mediasoup through a {@link RecvTransport}, which {@link RecvTransport#consume}
 * creates. Mediasoup has a consumer with the same id.
 *
 * <p>It is thread-safe. Its listeners run on the executor of its transport.
 */
public final class Consumer {

    private static final Logger logger = LoggerFactory.getLogger(Consumer.class);

    // Id.
    private final String id;
    // Local id.
    private final String localId;
    // Associated Producer id.
    private final String producerId;
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Associated RTCRtpReceiver.
    private final @Nullable RTCRtpReceiver rtpReceiver;
    // Remote track.
    private final MediaStreamTrack track;
    // Consumer kind.
    private final MediaKind kind;
    // RTP parameters.
    private final RtpParameters rtpParameters;
    // Paused flag.
    private volatile boolean paused;
    // App custom data.
    private final Map<String, @Nullable Object> appData;
    // The executor of the transport, where the listeners run.
    private final Executor executor;
    // Tells the transport that the application closed, paused or resumed the consumer.
    private final TransportListener transportListener;
    private final RunnableListeners transportCloseListeners = new RunnableListeners("transportclose", logger);
    // Observer instance.
    private final ConsumerObserver observer = new ConsumerObserver(logger);

    Consumer(
            String id,
            String localId,
            String producerId,
            @Nullable RTCRtpReceiver rtpReceiver,
            MediaStreamTrack track,
            MediaKind kind,
            RtpParameters rtpParameters,
            Map<String, @Nullable Object> appData,
            Executor executor,
            TransportListener transportListener) {
        logger.debug("constructor()");

        this.id = id;
        this.localId = localId;
        this.producerId = producerId;
        this.rtpReceiver = rtpReceiver;
        this.track = track;
        this.kind = kind;
        this.rtpParameters = rtpParameters;
        this.paused = !track.isEnabled();
        this.appData = appData;
        this.executor = executor;
        this.transportListener = transportListener;
    }

    /**
     * Returns the id of the consumer.
     *
     * @return the id of the mediasoup consumer
     */
    public String id() {
        return id;
    }

    /**
     * Returns the local id, which identifies the receiving in the handler.
     *
     * @return the local id
     */
    public String localId() {
        return localId;
    }

    /**
     * Returns the id of the producer being consumed.
     *
     * @return the id of the mediasoup producer
     */
    public String producerId() {
        return producerId;
    }

    /**
     * Returns whether the consumer is closed.
     *
     * @return whether it was closed, directly or by closing its transport
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Returns the media kind.
     *
     * @return the kind of the track
     */
    public MediaKind kind() {
        return kind;
    }

    /**
     * Returns the RTP receiver of the WebRTC stack.
     *
     * @return the receiver, or {@code null} if the handler does not expose one
     */
    public @Nullable RTCRtpReceiver rtpReceiver() {
        return rtpReceiver;
    }

    /**
     * Returns the track being received.
     *
     * @return the remote track
     */
    public MediaStreamTrack track() {
        return track;
    }

    /**
     * Returns the RTP parameters, which mediasoup gave to create the consumer.
     *
     * @return the RTP parameters
     */
    public RtpParameters rtpParameters() {
        return rtpParameters;
    }

    /**
     * Returns whether the consumer is paused.
     *
     * @return whether it is paused; it starts paused if the track is disabled
     */
    public boolean paused() {
        return paused;
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
     * @return the observer of this consumer
     */
    public ConsumerObserver observer() {
        return observer;
    }

    /**
     * Pauses receiving: it disables the track, and the transport makes the handler stop receiving its
     * RTP, together with the other consumers paused meanwhile, after its pending operations; a failure
     * of the handler is only logged. The observer emits {@code pause}. The application should pause the
     * mediasoup consumer too. It does nothing if the consumer is closed or already paused.
     */
    public void pause() {
        logger.debug("pause()");

        if (closed.get()) {
            logger.error("pause() | Consumer closed");
            return;
        }

        if (paused) {
            logger.debug("pause() | Consumer is already paused");
            return;
        }

        paused = true;
        track.setEnabled(false);

        transportListener.paused(this);

        // Emit observer event.
        executor.execute(observer::emitPause);
    }

    /**
     * Resumes receiving: it enables the track, and the transport makes the handler receive its RTP
     * again, together with the other consumers resumed meanwhile, after its pending operations; a
     * failure of the handler is only logged. The observer emits {@code resume}. The application should
     * resume the mediasoup consumer too. It does nothing if the consumer is closed or not paused.
     */
    public void resume() {
        logger.debug("resume()");

        if (closed.get()) {
            logger.error("resume() | Consumer closed");
            return;
        }

        if (!paused) {
            logger.debug("resume() | Consumer is already resumed");
            return;
        }

        paused = false;
        track.setEnabled(true);

        transportListener.resumed(this);

        // Emit observer event.
        executor.execute(observer::emitResume);
    }

    /**
     * Closes the consumer: the transport stops receiving its track, and the observer emits {@code
     * close}; after that no listener is called again. The application should close the mediasoup
     * consumer too. Calling it again does nothing.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("close()");

        transportListener.closed(this);

        executor.execute(() -> {
            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    /**
     * Listens for the transport of the consumer being closed, which closes the consumer.
     *
     * @param listener called once, instead of the consumer being closed by {@link #close()}
     * @return the subscription that removes the listener
     */
    public Subscription onTransportClose(Runnable listener) {
        return transportCloseListeners.add(listener);
    }

    /** Transport was closed. */
    void transportClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("transportClosed()");

        executor.execute(() -> {
            transportCloseListeners.emit();

            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    private void clearListeners() {
        transportCloseListeners.clear();
        observer.clear();
    }

    /** What the transport does when the application closes, pauses or resumes a consumer. */
    interface TransportListener {

        /**
         * The consumer was closed.
         *
         * @param consumer the consumer
         */
        void closed(Consumer consumer);

        /**
         * The consumer was paused.
         *
         * @param consumer the consumer
         */
        void paused(Consumer consumer);

        /**
         * The consumer was resumed.
         *
         * @param consumer the consumer
         */
        void resumed(Consumer consumer);
    }
}
