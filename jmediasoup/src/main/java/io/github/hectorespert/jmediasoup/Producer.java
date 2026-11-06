/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A track sent to mediasoup through a {@link SendTransport}, which {@link SendTransport#produce}
 * creates. Mediasoup has a producer with the same id.
 *
 * <p>It is thread-safe. Its listeners run on the executor of its transport.
 */
public final class Producer {

    private static final Logger logger = LoggerFactory.getLogger(Producer.class);

    // Id.
    private final String id;
    // Local id.
    private final String localId;
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Associated RTCRtpSender.
    private final @Nullable RTCRtpSender rtpSender;
    // Local track.
    private final MediaStreamTrack track;
    // Producer kind.
    private final MediaKind kind;
    // RTP parameters.
    private final RtpParameters rtpParameters;
    // Paused flag.
    private final boolean paused;
    // App custom data.
    private final Map<String, @Nullable Object> appData;
    // The executor of the transport, where the listeners run.
    private final Executor executor;
    // Tells the transport that the application closed the producer.
    private final Consumer<Producer> closeHandler;
    private final RunnableListeners transportCloseListeners = new RunnableListeners("transportclose", logger);
    // Observer instance.
    private final ProducerObserver observer = new ProducerObserver(logger);

    Producer(
            String id,
            String localId,
            @Nullable RTCRtpSender rtpSender,
            MediaStreamTrack track,
            MediaKind kind,
            RtpParameters rtpParameters,
            boolean disableTrackOnPause,
            Map<String, @Nullable Object> appData,
            Executor executor,
            Consumer<Producer> closeHandler) {
        logger.debug("constructor()");

        this.id = id;
        this.localId = localId;
        this.rtpSender = rtpSender;
        this.track = track;
        this.kind = kind;
        this.rtpParameters = rtpParameters;
        this.paused = disableTrackOnPause && !track.isEnabled();
        this.appData = appData;
        this.executor = executor;
        this.closeHandler = closeHandler;
    }

    /**
     * Returns the id of the producer.
     *
     * @return the id of the mediasoup producer
     */
    public String id() {
        return id;
    }

    /**
     * Returns the local id, which identifies the sending in the handler.
     *
     * @return the local id
     */
    public String localId() {
        return localId;
    }

    /**
     * Returns whether the producer is closed.
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
     * Returns the RTP sender of the WebRTC stack.
     *
     * @return the sender, or {@code null} if the handler does not expose one
     */
    public @Nullable RTCRtpSender rtpSender() {
        return rtpSender;
    }

    /**
     * Returns the track being sent.
     *
     * @return the track
     */
    public MediaStreamTrack track() {
        return track;
    }

    /**
     * Returns the RTP parameters, which were sent to mediasoup to create the producer.
     *
     * @return the RTP parameters
     */
    public RtpParameters rtpParameters() {
        return rtpParameters;
    }

    /**
     * Returns whether the producer is paused.
     *
     * @return whether it is paused; it starts paused if the track was disabled and {@link
     *     ProducerOptions#disableTrackOnPause()} is set
     */
    public boolean paused() {
        return paused;
    }

    /**
     * Returns the highest spatial layer being sent.
     *
     * @return the layer, or {@code null} if it was never limited
     */
    public @Nullable Integer maxSpatialLayer() {
        // Nothing limits it until setMaxSpatialLayer exists.
        return null;
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
     * @return the observer of this producer
     */
    public ProducerObserver observer() {
        return observer;
    }

    /**
     * Closes the producer: the transport stops sending its track, and the observer emits {@code
     * close}; after that no listener is called again. The application should close the mediasoup
     * producer too. Calling it again does nothing.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("close()");

        closeHandler.accept(this);

        executor.execute(() -> {
            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    /**
     * Listens for the transport of the producer being closed, which closes the producer.
     *
     * @param listener called once, instead of the producer being closed by {@link #close()}
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
}
