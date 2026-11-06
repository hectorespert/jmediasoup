/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The options to produce a track. Use {@link #builder(MediaStreamTrack)}, which starts with the
 * defaults of mediasoup-client.
 *
 * @param track the track to send
 * @param streamId the media stream id, shared by the tracks that should be played in sync, or
 *     {@code null} to use one per track; from mediasoup-client
 * @param encodings the encodings, several for simulcast; empty to use the defaults of the handler
 * @param codecOptions the codec settings, or {@code null} if absent
 * @param codec the codec to use among those of the device, or {@code null} to use the first
 *     suitable one
 * @param stopTracks whether the track is stopped when the producer closes; {@code true} by default
 * @param disableTrackOnPause whether the track is disabled while the producer is paused;
 *     {@code true} by default
 * @param zeroRtpOnPause whether no RTP is sent while the producer is paused; {@code false} by
 *     default
 * @param appData custom application data, which may contain {@code null} values
 */
public record ProducerOptions(
        MediaStreamTrack track,
        @Nullable String streamId,
        List<RtpEncodingParameters> encodings,
        @Nullable ProducerCodecOptions codecOptions,
        @Nullable RtpCodecCapability codec,
        boolean stopTracks,
        boolean disableTrackOnPause,
        boolean zeroRtpOnPause,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the collections, with absent ones as empty. */
    public ProducerOptions {
        requireNonNull(track, "track");
        encodings = Checks.list(encodings, "encodings");
        appData = Checks.appData(appData);
    }

    /**
     * Returns a builder for a track, with {@code stopTracks} and {@code disableTrackOnPause} set to
     * {@code true}, {@code zeroRtpOnPause} set to {@code false} and every other field absent.
     *
     * @param track the track to send
     * @return a new builder
     */
    public static Builder builder(MediaStreamTrack track) {
        return new Builder(track);
    }

    /**
     * Returns a builder with the fields of these options, to create a modified copy.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(track)
                .streamId(streamId)
                .encodings(encodings)
                .codecOptions(codecOptions)
                .codec(codec)
                .stopTracks(stopTracks)
                .disableTrackOnPause(disableTrackOnPause)
                .zeroRtpOnPause(zeroRtpOnPause)
                .appData(appData);
    }

    /** Builder of {@link ProducerOptions}. Setters of optional fields accept {@code null}. */
    public static final class Builder {

        private final MediaStreamTrack track;
        private @Nullable String streamId;
        private @Nullable List<RtpEncodingParameters> encodings;
        private @Nullable ProducerCodecOptions codecOptions;
        private @Nullable RtpCodecCapability codec;
        private boolean stopTracks = true;
        private boolean disableTrackOnPause = true;
        private boolean zeroRtpOnPause;
        private @Nullable Map<String, ? extends @Nullable Object> appData;

        private Builder(MediaStreamTrack track) {
            this.track = track;
        }

        /**
         * Sets {@link ProducerOptions#streamId()}.
         *
         * @param streamId the media stream id
         * @return this builder
         */
        public Builder streamId(@Nullable String streamId) {
            this.streamId = streamId;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#encodings()}.
         *
         * @param encodings the encodings
         * @return this builder
         */
        public Builder encodings(@Nullable List<RtpEncodingParameters> encodings) {
            this.encodings = encodings;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#codecOptions()}.
         *
         * @param codecOptions the codec settings
         * @return this builder
         */
        public Builder codecOptions(@Nullable ProducerCodecOptions codecOptions) {
            this.codecOptions = codecOptions;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#codec()}.
         *
         * @param codec the codec to use
         * @return this builder
         */
        public Builder codec(@Nullable RtpCodecCapability codec) {
            this.codec = codec;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#stopTracks()}.
         *
         * @param stopTracks whether the track is stopped when the producer closes
         * @return this builder
         */
        public Builder stopTracks(boolean stopTracks) {
            this.stopTracks = stopTracks;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#disableTrackOnPause()}.
         *
         * @param disableTrackOnPause whether the track is disabled while paused
         * @return this builder
         */
        public Builder disableTrackOnPause(boolean disableTrackOnPause) {
            this.disableTrackOnPause = disableTrackOnPause;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#zeroRtpOnPause()}.
         *
         * @param zeroRtpOnPause whether no RTP is sent while paused
         * @return this builder
         */
        public Builder zeroRtpOnPause(boolean zeroRtpOnPause) {
            this.zeroRtpOnPause = zeroRtpOnPause;
            return this;
        }

        /**
         * Sets {@link ProducerOptions#appData()}.
         *
         * @param appData custom application data
         * @return this builder
         */
        public Builder appData(@Nullable Map<String, ? extends @Nullable Object> appData) {
            this.appData = appData;
            return this;
        }

        /**
         * Creates the options.
         *
         * @return the options
         */
        public ProducerOptions build() {
            return new ProducerOptions(
                    track,
                    streamId,
                    encodings,
                    codecOptions,
                    codec,
                    stopTracks,
                    disableTrackOnPause,
                    zeroRtpOnPause,
                    Checks.appData(appData));
        }
    }
}
