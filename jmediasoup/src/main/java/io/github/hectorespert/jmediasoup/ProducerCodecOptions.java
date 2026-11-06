/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * Codec settings that a producer asks mediasoup to use, applied to the SDP answer. Every field is
 * optional and {@code null} when absent; use {@link #builder()} to create them.
 *
 * @param opusStereo whether Opus is sent in stereo
 * @param opusFec whether Opus uses in-band forward error correction
 * @param opusDtx whether Opus uses discontinuous transmission
 * @param opusMaxPlaybackRate the maximum Opus playback rate, in hertz
 * @param opusMaxAverageBitrate the maximum average Opus bitrate, in bits per second
 * @param opusPtime the Opus packet time, in milliseconds
 * @param opusNack whether Opus uses NACK retransmissions, from mediasoup-client
 * @param videoGoogleStartBitrate the initial video bitrate, in kilobits per second
 * @param videoGoogleMaxBitrate the maximum video bitrate, in kilobits per second
 * @param videoGoogleMinBitrate the minimum video bitrate, in kilobits per second
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProducerCodecOptions(
        @Nullable Boolean opusStereo,
        @Nullable Boolean opusFec,
        @Nullable Boolean opusDtx,
        @Nullable Integer opusMaxPlaybackRate,
        @Nullable Integer opusMaxAverageBitrate,
        @Nullable Integer opusPtime,
        @Nullable Boolean opusNack,
        @Nullable Integer videoGoogleStartBitrate,
        @Nullable Integer videoGoogleMaxBitrate,
        @Nullable Integer videoGoogleMinBitrate) {

    /** Checks the fields. */
    public ProducerCodecOptions {
        Checks.range(opusMaxPlaybackRate, 1, Integer.MAX_VALUE, "opusMaxPlaybackRate");
        Checks.range(opusMaxAverageBitrate, 1, Integer.MAX_VALUE, "opusMaxAverageBitrate");
        Checks.range(opusPtime, 1, Integer.MAX_VALUE, "opusPtime");
        Checks.range(videoGoogleStartBitrate, 1, Integer.MAX_VALUE, "videoGoogleStartBitrate");
        Checks.range(videoGoogleMaxBitrate, 1, Integer.MAX_VALUE, "videoGoogleMaxBitrate");
        Checks.range(videoGoogleMinBitrate, 1, Integer.MAX_VALUE, "videoGoogleMinBitrate");
    }

    /**
     * Returns a builder with every field absent.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder with the fields of these options, to create a modified copy.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder()
                .opusStereo(opusStereo)
                .opusFec(opusFec)
                .opusDtx(opusDtx)
                .opusMaxPlaybackRate(opusMaxPlaybackRate)
                .opusMaxAverageBitrate(opusMaxAverageBitrate)
                .opusPtime(opusPtime)
                .opusNack(opusNack)
                .videoGoogleStartBitrate(videoGoogleStartBitrate)
                .videoGoogleMaxBitrate(videoGoogleMaxBitrate)
                .videoGoogleMinBitrate(videoGoogleMinBitrate);
    }

    /** Builder of {@link ProducerCodecOptions}. Each setter accepts {@code null} to leave the field absent. */
    public static final class Builder {

        private @Nullable Boolean opusStereo;
        private @Nullable Boolean opusFec;
        private @Nullable Boolean opusDtx;
        private @Nullable Integer opusMaxPlaybackRate;
        private @Nullable Integer opusMaxAverageBitrate;
        private @Nullable Integer opusPtime;
        private @Nullable Boolean opusNack;
        private @Nullable Integer videoGoogleStartBitrate;
        private @Nullable Integer videoGoogleMaxBitrate;
        private @Nullable Integer videoGoogleMinBitrate;

        private Builder() {}

        /**
         * Sets {@link ProducerCodecOptions#opusStereo()}.
         *
         * @param opusStereo whether Opus is sent in stereo
         * @return this builder
         */
        public Builder opusStereo(@Nullable Boolean opusStereo) {
            this.opusStereo = opusStereo;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusFec()}.
         *
         * @param opusFec whether Opus uses in-band forward error correction
         * @return this builder
         */
        public Builder opusFec(@Nullable Boolean opusFec) {
            this.opusFec = opusFec;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusDtx()}.
         *
         * @param opusDtx whether Opus uses discontinuous transmission
         * @return this builder
         */
        public Builder opusDtx(@Nullable Boolean opusDtx) {
            this.opusDtx = opusDtx;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusMaxPlaybackRate()}.
         *
         * @param opusMaxPlaybackRate the maximum Opus playback rate, in hertz
         * @return this builder
         */
        public Builder opusMaxPlaybackRate(@Nullable Integer opusMaxPlaybackRate) {
            this.opusMaxPlaybackRate = opusMaxPlaybackRate;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusMaxAverageBitrate()}.
         *
         * @param opusMaxAverageBitrate the maximum average Opus bitrate, in bits per second
         * @return this builder
         */
        public Builder opusMaxAverageBitrate(@Nullable Integer opusMaxAverageBitrate) {
            this.opusMaxAverageBitrate = opusMaxAverageBitrate;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusPtime()}.
         *
         * @param opusPtime the Opus packet time, in milliseconds
         * @return this builder
         */
        public Builder opusPtime(@Nullable Integer opusPtime) {
            this.opusPtime = opusPtime;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#opusNack()}.
         *
         * @param opusNack whether Opus uses NACK retransmissions
         * @return this builder
         */
        public Builder opusNack(@Nullable Boolean opusNack) {
            this.opusNack = opusNack;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#videoGoogleStartBitrate()}.
         *
         * @param videoGoogleStartBitrate the initial video bitrate, in kilobits per second
         * @return this builder
         */
        public Builder videoGoogleStartBitrate(@Nullable Integer videoGoogleStartBitrate) {
            this.videoGoogleStartBitrate = videoGoogleStartBitrate;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#videoGoogleMaxBitrate()}.
         *
         * @param videoGoogleMaxBitrate the maximum video bitrate, in kilobits per second
         * @return this builder
         */
        public Builder videoGoogleMaxBitrate(@Nullable Integer videoGoogleMaxBitrate) {
            this.videoGoogleMaxBitrate = videoGoogleMaxBitrate;
            return this;
        }

        /**
         * Sets {@link ProducerCodecOptions#videoGoogleMinBitrate()}.
         *
         * @param videoGoogleMinBitrate the minimum video bitrate, in kilobits per second
         * @return this builder
         */
        public Builder videoGoogleMinBitrate(@Nullable Integer videoGoogleMinBitrate) {
            this.videoGoogleMinBitrate = videoGoogleMinBitrate;
            return this;
        }

        /**
         * Creates the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a field is out of range
         */
        public ProducerCodecOptions build() {
            return new ProducerCodecOptions(
                    opusStereo,
                    opusFec,
                    opusDtx,
                    opusMaxPlaybackRate,
                    opusMaxAverageBitrate,
                    opusPtime,
                    opusNack,
                    videoGoogleStartBitrate,
                    videoGoogleMaxBitrate,
                    videoGoogleMinBitrate);
        }
    }
}
