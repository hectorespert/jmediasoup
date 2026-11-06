/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The options to produce data. Every field is optional; use {@link #builder()} to create them.
 *
 * <p>A data channel is either ordered and reliable, or unordered with at most one of
 * {@code maxPacketLifeTime} and {@code maxRetransmits}. Combinations that contradict this fail on
 * construction. An absent {@code ordered} means unordered if one of those limits is given, and
 * ordered otherwise.
 *
 * @param ordered whether messages are delivered in order, and therefore reliably, or {@code null} if
 *     absent
 * @param maxPacketLifeTime for unordered channels, the time in milliseconds after which a message is
 *     no longer retransmitted, or {@code null} if absent
 * @param maxRetransmits for unordered channels, the maximum number of retransmissions, or
 *     {@code null} if absent
 * @param label the label of the data channel, or {@code null} if absent
 * @param protocol the sub-protocol of the data channel, or {@code null} if absent
 * @param appData custom application data, which may contain {@code null} values
 */
public record DataProducerOptions(
        @Nullable Boolean ordered,
        @Nullable Integer maxPacketLifeTime,
        @Nullable Integer maxRetransmits,
        @Nullable String label,
        @Nullable String protocol,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the application data, with absent data as empty. */
    public DataProducerOptions {
        Checks.reliability(ordered, maxPacketLifeTime, maxRetransmits);
        appData = Checks.appData(appData);
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
                .ordered(ordered)
                .maxPacketLifeTime(maxPacketLifeTime)
                .maxRetransmits(maxRetransmits)
                .label(label)
                .protocol(protocol)
                .appData(appData);
    }

    /** Builder of {@link DataProducerOptions}. Each setter accepts {@code null} to leave the field absent. */
    public static final class Builder {

        private @Nullable Boolean ordered;
        private @Nullable Integer maxPacketLifeTime;
        private @Nullable Integer maxRetransmits;
        private @Nullable String label;
        private @Nullable String protocol;
        private @Nullable Map<String, ? extends @Nullable Object> appData;

        private Builder() {}

        /**
         * Sets {@link DataProducerOptions#ordered()}.
         *
         * @param ordered whether messages are delivered in order
         * @return this builder
         */
        public Builder ordered(@Nullable Boolean ordered) {
            this.ordered = ordered;
            return this;
        }

        /**
         * Sets {@link DataProducerOptions#maxPacketLifeTime()}.
         *
         * @param maxPacketLifeTime the retransmission time limit, in milliseconds
         * @return this builder
         */
        public Builder maxPacketLifeTime(@Nullable Integer maxPacketLifeTime) {
            this.maxPacketLifeTime = maxPacketLifeTime;
            return this;
        }

        /**
         * Sets {@link DataProducerOptions#maxRetransmits()}.
         *
         * @param maxRetransmits the maximum number of retransmissions
         * @return this builder
         */
        public Builder maxRetransmits(@Nullable Integer maxRetransmits) {
            this.maxRetransmits = maxRetransmits;
            return this;
        }

        /**
         * Sets {@link DataProducerOptions#label()}.
         *
         * @param label the label of the data channel
         * @return this builder
         */
        public Builder label(@Nullable String label) {
            this.label = label;
            return this;
        }

        /**
         * Sets {@link DataProducerOptions#protocol()}.
         *
         * @param protocol the sub-protocol of the data channel
         * @return this builder
         */
        public Builder protocol(@Nullable String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * Sets {@link DataProducerOptions#appData()}.
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
         * @throws IllegalArgumentException if the reliability settings contradict each other
         */
        public DataProducerOptions build() {
            return new DataProducerOptions(
                    ordered, maxPacketLifeTime, maxRetransmits, label, protocol, Checks.appData(appData));
        }
    }
}
