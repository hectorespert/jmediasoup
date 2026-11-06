/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * An encoding, which is a media RTP stream and its RTX stream if any. Every field is optional and
 * {@code null} when absent; use {@link #builder()} to create one.
 *
 * @param active whether the encoding is sent, or {@code null} if absent (sent)
 * @param ssrc the media SSRC, between 0 and 2<sup>32</sup>-1
 * @param rid the RID RTP extension value, unique within the parameters
 * @param codecPayloadType the payload type of the codec this encoding uses; if absent, the first
 *     media codec
 * @param rtx the RTX stream
 * @param dtx whether discontinuous transmission is used, for audio with a codec that supports it or
 *     for screen sharing; or {@code null} if absent (not used)
 * @param scalabilityMode the number of spatial and temporal layers, such as {@code L1T3}
 * @param scaleResolutionDownBy the factor by which the video resolution is scaled down, at least 1
 * @param maxBitrate the maximum bitrate, in bits per second
 * @param maxFramerate the maximum frame rate, in frames per second
 * @param adaptivePtime whether the audio packet time adapts to the network
 * @param priority the priority for bandwidth allocation
 * @param networkPriority the priority for the network (DSCP)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RtpEncodingParameters(
        @Nullable Boolean active,
        @Nullable Long ssrc,
        @Nullable String rid,
        @Nullable Integer codecPayloadType,
        @Nullable Rtx rtx,
        @Nullable Boolean dtx,
        @Nullable String scalabilityMode,
        @Nullable Double scaleResolutionDownBy,
        @Nullable Integer maxBitrate,
        @Nullable Double maxFramerate,
        @Nullable Boolean adaptivePtime,
        @Nullable Priority priority,
        @Nullable Priority networkPriority) {

    /** Checks the fields. */
    public RtpEncodingParameters {
        Checks.ssrc(ssrc, "ssrc");
        Checks.range(codecPayloadType, 0, 127, "codecPayloadType");
        Checks.atLeast(scaleResolutionDownBy, 1.0, "scaleResolutionDownBy");
        Checks.range(maxBitrate, 0, Integer.MAX_VALUE, "maxBitrate");
        Checks.atLeast(maxFramerate, 0.0, "maxFramerate");
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
     * Returns a builder with the fields of this encoding, to create a modified copy.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder()
                .active(active)
                .ssrc(ssrc)
                .rid(rid)
                .codecPayloadType(codecPayloadType)
                .rtx(rtx)
                .dtx(dtx)
                .scalabilityMode(scalabilityMode)
                .scaleResolutionDownBy(scaleResolutionDownBy)
                .maxBitrate(maxBitrate)
                .maxFramerate(maxFramerate)
                .adaptivePtime(adaptivePtime)
                .priority(priority)
                .networkPriority(networkPriority);
    }

    /** Builder of {@link RtpEncodingParameters}. Each setter accepts {@code null} to leave the field absent. */
    public static final class Builder {

        private @Nullable Boolean active;
        private @Nullable Long ssrc;
        private @Nullable String rid;
        private @Nullable Integer codecPayloadType;
        private @Nullable Rtx rtx;
        private @Nullable Boolean dtx;
        private @Nullable String scalabilityMode;
        private @Nullable Double scaleResolutionDownBy;
        private @Nullable Integer maxBitrate;
        private @Nullable Double maxFramerate;
        private @Nullable Boolean adaptivePtime;
        private @Nullable Priority priority;
        private @Nullable Priority networkPriority;

        private Builder() {}

        /**
         * Sets {@link RtpEncodingParameters#active()}.
         *
         * @param active whether the encoding is sent
         * @return this builder
         */
        public Builder active(@Nullable Boolean active) {
            this.active = active;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#ssrc()}.
         *
         * @param ssrc the media SSRC
         * @return this builder
         */
        public Builder ssrc(@Nullable Long ssrc) {
            this.ssrc = ssrc;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#rid()}.
         *
         * @param rid the RID RTP extension value
         * @return this builder
         */
        public Builder rid(@Nullable String rid) {
            this.rid = rid;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#codecPayloadType()}.
         *
         * @param codecPayloadType the payload type of the codec
         * @return this builder
         */
        public Builder codecPayloadType(@Nullable Integer codecPayloadType) {
            this.codecPayloadType = codecPayloadType;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#rtx()}.
         *
         * @param rtx the RTX stream
         * @return this builder
         */
        public Builder rtx(@Nullable Rtx rtx) {
            this.rtx = rtx;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#dtx()}.
         *
         * @param dtx whether discontinuous transmission is used
         * @return this builder
         */
        public Builder dtx(@Nullable Boolean dtx) {
            this.dtx = dtx;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#scalabilityMode()}.
         *
         * @param scalabilityMode the number of spatial and temporal layers, such as {@code L1T3}
         * @return this builder
         */
        public Builder scalabilityMode(@Nullable String scalabilityMode) {
            this.scalabilityMode = scalabilityMode;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#scaleResolutionDownBy()}.
         *
         * @param scaleResolutionDownBy the factor by which the resolution is scaled down
         * @return this builder
         */
        public Builder scaleResolutionDownBy(@Nullable Double scaleResolutionDownBy) {
            this.scaleResolutionDownBy = scaleResolutionDownBy;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#maxBitrate()}.
         *
         * @param maxBitrate the maximum bitrate, in bits per second
         * @return this builder
         */
        public Builder maxBitrate(@Nullable Integer maxBitrate) {
            this.maxBitrate = maxBitrate;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#maxFramerate()}.
         *
         * @param maxFramerate the maximum frame rate
         * @return this builder
         */
        public Builder maxFramerate(@Nullable Double maxFramerate) {
            this.maxFramerate = maxFramerate;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#adaptivePtime()}.
         *
         * @param adaptivePtime whether the audio packet time adapts to the network
         * @return this builder
         */
        public Builder adaptivePtime(@Nullable Boolean adaptivePtime) {
            this.adaptivePtime = adaptivePtime;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#priority()}.
         *
         * @param priority the priority for bandwidth allocation
         * @return this builder
         */
        public Builder priority(@Nullable Priority priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Sets {@link RtpEncodingParameters#networkPriority()}.
         *
         * @param networkPriority the priority for the network
         * @return this builder
         */
        public Builder networkPriority(@Nullable Priority networkPriority) {
            this.networkPriority = networkPriority;
            return this;
        }

        /**
         * Creates the encoding.
         *
         * @return the encoding
         * @throws IllegalArgumentException if a field is out of range
         */
        public RtpEncodingParameters build() {
            return new RtpEncodingParameters(
                    active,
                    ssrc,
                    rid,
                    codecPayloadType,
                    rtx,
                    dtx,
                    scalabilityMode,
                    scaleResolutionDownBy,
                    maxBitrate,
                    maxFramerate,
                    adaptivePtime,
                    priority,
                    networkPriority);
        }
    }
}
