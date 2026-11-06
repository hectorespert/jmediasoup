/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The options to create a transport: the parameters of the mediasoup transport, as returned by the
 * server, plus the local ICE settings and the application data.
 *
 * <p>The first four fields come from the server, so the record can be read from its response with
 * the user's {@code ObjectMapper}; {@link #toBuilder()} then adds the local settings. Use
 * {@link #builder} to create it from scratch.
 *
 * @param id the id of the mediasoup transport
 * @param iceParameters its ICE parameters
 * @param iceCandidates its ICE candidates
 * @param dtlsParameters its DTLS parameters
 * @param sctpParameters its SCTP parameters, or {@code null} if SCTP is not enabled
 * @param iceServers the STUN and TURN servers to use
 * @param iceTransportPolicy which ICE candidates may be used, or {@code null} if absent (all)
 * @param appData custom application data, which may contain {@code null} values
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransportOptions(
        String id,
        IceParameters iceParameters,
        List<IceCandidate> iceCandidates,
        DtlsParameters dtlsParameters,
        @Nullable SctpParameters sctpParameters,
        List<IceServer> iceServers,
        @Nullable IceTransportPolicy iceTransportPolicy,
        Map<String, @Nullable Object> appData) {

    /** Checks the fields and copies the collections, with absent ones as empty. */
    public TransportOptions {
        Checks.notEmpty(id, "id");
        requireNonNull(iceParameters, "iceParameters");
        iceCandidates = Checks.list(iceCandidates, "iceCandidates");
        requireNonNull(dtlsParameters, "dtlsParameters");
        iceServers = Checks.list(iceServers, "iceServers");
        appData = Checks.appData(appData);
    }

    /**
     * Returns a builder with the parameters of the mediasoup transport and every other field absent.
     *
     * @param id the id of the mediasoup transport
     * @param iceParameters its ICE parameters
     * @param iceCandidates its ICE candidates
     * @param dtlsParameters its DTLS parameters
     * @return a new builder
     */
    public static Builder builder(
            String id, IceParameters iceParameters, List<IceCandidate> iceCandidates, DtlsParameters dtlsParameters) {
        return new Builder(id, iceParameters, iceCandidates, dtlsParameters);
    }

    /**
     * Returns a builder with the fields of these options, to create a modified copy.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(id, iceParameters, iceCandidates, dtlsParameters)
                .sctpParameters(sctpParameters)
                .iceServers(iceServers)
                .iceTransportPolicy(iceTransportPolicy)
                .appData(appData);
    }

    /** Builder of {@link TransportOptions}. Each setter accepts {@code null} to leave the field absent. */
    public static final class Builder {

        private final String id;
        private final IceParameters iceParameters;
        private final List<IceCandidate> iceCandidates;
        private final DtlsParameters dtlsParameters;
        private @Nullable SctpParameters sctpParameters;
        private @Nullable List<IceServer> iceServers;
        private @Nullable IceTransportPolicy iceTransportPolicy;
        private @Nullable Map<String, ? extends @Nullable Object> appData;

        private Builder(
                String id,
                IceParameters iceParameters,
                List<IceCandidate> iceCandidates,
                DtlsParameters dtlsParameters) {
            this.id = id;
            this.iceParameters = iceParameters;
            this.iceCandidates = iceCandidates;
            this.dtlsParameters = dtlsParameters;
        }

        /**
         * Sets {@link TransportOptions#sctpParameters()}.
         *
         * @param sctpParameters the SCTP parameters of the mediasoup transport
         * @return this builder
         */
        public Builder sctpParameters(@Nullable SctpParameters sctpParameters) {
            this.sctpParameters = sctpParameters;
            return this;
        }

        /**
         * Sets {@link TransportOptions#iceServers()}.
         *
         * @param iceServers the STUN and TURN servers
         * @return this builder
         */
        public Builder iceServers(@Nullable List<IceServer> iceServers) {
            this.iceServers = iceServers;
            return this;
        }

        /**
         * Sets {@link TransportOptions#iceTransportPolicy()}.
         *
         * @param iceTransportPolicy which ICE candidates may be used
         * @return this builder
         */
        public Builder iceTransportPolicy(@Nullable IceTransportPolicy iceTransportPolicy) {
            this.iceTransportPolicy = iceTransportPolicy;
            return this;
        }

        /**
         * Sets {@link TransportOptions#appData()}.
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
         * @throws IllegalArgumentException if a field is not valid
         */
        public TransportOptions build() {
            return new TransportOptions(
                    id,
                    iceParameters,
                    iceCandidates,
                    dtlsParameters,
                    sctpParameters,
                    iceServers,
                    iceTransportPolicy,
                    Checks.appData(appData));
        }
    }
}
