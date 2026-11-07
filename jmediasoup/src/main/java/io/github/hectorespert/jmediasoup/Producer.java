/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackEndedListener;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile @Nullable MediaStreamTrack track;
    // Producer kind.
    private final MediaKind kind;
    // RTP parameters.
    private final RtpParameters rtpParameters;
    // Paused flag.
    private volatile boolean paused;
    // Whether the Producer should disable the track when paused.
    private final boolean disableTrackOnPause;
    // Whether we should mark the transceiver as inactive when paused.
    private final boolean zeroRtpOnPause;
    // App custom data.
    private final Map<String, @Nullable Object> appData;
    // The executor of the transport, where the listeners run.
    private final Executor executor;
    // Tells the transport that the application closed, paused or resumed the producer, or replaced its
    // track.
    private final TransportListener transportListener;
    private final RunnableListeners transportCloseListeners = new RunnableListeners("transportclose", logger);
    private final RunnableListeners trackEndedListeners = new RunnableListeners("trackended", logger);
    // Listens for the end of the track, which webrtc-java notifies on its own threads.
    private final MediaStreamTrackEndedListener trackEndedListener;
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
            boolean zeroRtpOnPause,
            Map<String, @Nullable Object> appData,
            Executor executor,
            TransportListener transportListener) {
        logger.debug("constructor()");

        this.id = id;
        this.localId = localId;
        this.rtpSender = rtpSender;
        this.track = track;
        this.kind = kind;
        this.rtpParameters = rtpParameters;
        this.paused = disableTrackOnPause && !track.isEnabled();
        this.disableTrackOnPause = disableTrackOnPause;
        this.zeroRtpOnPause = zeroRtpOnPause;
        this.appData = appData;
        this.executor = executor;
        this.transportListener = transportListener;
        this.trackEndedListener = ended -> executor.execute(this::onTrackEnded);

        handleTrack();
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
     * @return the track, or {@code null} if {@link #replaceTrack} replaced it with none
     */
    public @Nullable MediaStreamTrack track() {
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
     * Pauses sending: it disables the track if {@link ProducerOptions#disableTrackOnPause()} is set,
     * and, if {@link ProducerOptions#zeroRtpOnPause()} is set, the transport makes the handler stop
     * sending RTP, after its pending operations; a failure of the handler is only logged. The observer
     * emits {@code pause}. The application should pause the mediasoup producer too. It does nothing if
     * the producer is closed.
     */
    public void pause() {
        logger.debug("pause()");

        if (closed.get()) {
            logger.error("pause() | Producer closed");
            return;
        }

        paused = true;

        MediaStreamTrack track = this.track;
        if (track != null && disableTrackOnPause) {
            track.setEnabled(false);
        }

        if (zeroRtpOnPause) {
            transportListener.paused(this);
        }

        // Emit observer event.
        executor.execute(observer::emitPause);
    }

    /**
     * Resumes sending: it enables the track if {@link ProducerOptions#disableTrackOnPause()} is set,
     * and, if {@link ProducerOptions#zeroRtpOnPause()} is set, the transport makes the handler send RTP
     * again, after its pending operations; a failure of the handler is only logged. The observer emits
     * {@code resume}. The application should resume the mediasoup producer too. It does nothing if the
     * producer is closed.
     */
    public void resume() {
        logger.debug("resume()");

        if (closed.get()) {
            logger.error("resume() | Producer closed");
            return;
        }

        paused = false;

        MediaStreamTrack track = this.track;
        if (track != null && disableTrackOnPause) {
            track.setEnabled(true);
        }

        if (zeroRtpOnPause) {
            transportListener.resumed(this);
        }

        // Emit observer event.
        executor.execute(observer::emitResume);
    }

    /**
     * Replaces the track being sent, without renegotiating, after the pending operations of the
     * transport. The new track takes the pause state of the producer if {@link
     * ProducerOptions#disableTrackOnPause()} is set. The previous track is not released: it belongs to
     * the application.
     *
     * @param track the new track, of the same kind, or {@code null} to send nothing
     * @return a future that completes once the handler sends the new track; it fails with an {@link
     *     InvalidStateException} if the producer is closed, before or while replacing, or if the track
     *     has ended, or with the failure of the handler
     */
    public CompletableFuture<Void> replaceTrack(@Nullable MediaStreamTrack track) {
        logger.debug("replaceTrack() [track:{}]", track == null ? null : track.getId());

        if (closed.get()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        } else if (track != null && track.getState() == MediaStreamTrackState.ENDED) {
            return CompletableFuture.failedFuture(new InvalidStateException("track ended"));
        }

        // Do nothing if this is the same track as the current handled one.
        if (track == this.track) {
            logger.debug("replaceTrack() | same track, ignored");
            return CompletableFuture.completedFuture(null);
        }

        return transportListener
                .replaceTrack(this, track)
                .thenRunAsync(
                        () -> {
                            if (closed.get()) {
                                throw new CompletionException(new InvalidStateException("closed"));
                            }

                            // Destroy the previous track.
                            destroyTrack();

                            // Set the new track.
                            this.track = track;

                            // If this Producer was paused/resumed and the state of the new
                            // track does not match, fix it.
                            if (track != null && disableTrackOnPause) {
                                track.setEnabled(!paused);
                            }

                            // Handle the effective track.
                            handleTrack();
                        },
                        executor);
    }

    /**
     * Returns the statistics of the RTP sender of the producer.
     *
     * @return a future with the report; it fails with an {@link InvalidStateException} if the producer or
     *     its transport is closed, or with the failure of the handler
     */
    public CompletableFuture<RTCStatsReport> getStats() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new InvalidStateException("closed"));
        }

        return transportListener.getStats(this);
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

        destroyTrack();

        transportListener.closed(this);

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

    /**
     * Listens for the end of the track being sent, as when its device is unplugged. The producer is
     * not closed.
     *
     * @param listener called when the track ends
     * @return the subscription that removes the listener
     */
    public Subscription onTrackEnded(Runnable listener) {
        return trackEndedListeners.add(listener);
    }

    /** Transport was closed. */
    void transportClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("transportClosed()");

        destroyTrack();

        executor.execute(() -> {
            transportCloseListeners.emit();

            // Emit observer event.
            observer.emitClose();

            clearListeners();
        });
    }

    private void onTrackEnded() {
        logger.debug("track \"ended\" event");

        trackEndedListeners.emit();

        // Emit observer event.
        observer.emitTrackEnded();
    }

    private void handleTrack() {
        MediaStreamTrack track = this.track;
        if (track == null) {
            return;
        }

        track.addTrackEndedListener(trackEndedListener);
    }

    // Stops listening to the track. The track is not stopped, whatever stopTracks says: it belongs to
    // the application (8.2.13).
    private void destroyTrack() {
        MediaStreamTrack track = this.track;
        if (track == null) {
            return;
        }

        track.removeTrackEndedListener(trackEndedListener);
    }

    private void clearListeners() {
        transportCloseListeners.clear();
        trackEndedListeners.clear();
        observer.clear();
    }

    /**
     * What the transport does when the application closes, pauses or resumes a producer, or replaces
     * its track.
     */
    interface TransportListener {

        /**
         * The producer was closed.
         *
         * @param producer the producer
         */
        void closed(Producer producer);

        /**
         * The producer was paused, with {@link ProducerOptions#zeroRtpOnPause()} set.
         *
         * @param producer the producer
         */
        void paused(Producer producer);

        /**
         * The producer was resumed, with {@link ProducerOptions#zeroRtpOnPause()} set.
         *
         * @param producer the producer
         */
        void resumed(Producer producer);

        /**
         * The application replaces the track of the producer.
         *
         * @param producer the producer
         * @param track the new track, or {@code null}
         * @return a future that completes once the handler sends the new track
         */
        CompletableFuture<Void> replaceTrack(Producer producer, @Nullable MediaStreamTrack track);

        /**
         * The application asks for the statistics of the producer.
         *
         * @param producer the producer
         * @return a future with the statistics of its RTP sender
         */
        CompletableFuture<RTCStatsReport> getStats(Producer producer);
    }
}
