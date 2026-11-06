/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import io.github.hectorespert.jmediasoup.ConsumerOptions;
import io.github.hectorespert.jmediasoup.DataConsumerOptions;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.transport.DtlsFingerprint;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.FingerprintAlgorithm;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parameters that a mediasoup server would send, for tests without a server. Port of
 * {@code tests/fake_parameters.py} of pymediasoup; the data lives in {@code fixtures/*.json}, and
 * every call returns new ids, as the original does.
 */
public final class FakeParameters {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Map<String, String> CONSUMER_FIXTURES = Map.of(
            "audio/opus", "consumer-audio-opus",
            "audio/ISAC", "consumer-audio-isac",
            "video/VP8", "consumer-video-vp8");

    private FakeParameters() {}

    /** The RTP capabilities of the router, {@code generateRouterRtpCapabilities}. */
    public static RtpCapabilities routerRtpCapabilities() {
        return read("router-rtp-capabilities", RtpCapabilities.class);
    }

    /** The native RTP capabilities of pymediasoup's {@code FakeHandler}. */
    public static RtpCapabilities nativeRtpCapabilities() {
        return read("native-rtp-capabilities", RtpCapabilities.class);
    }

    /**
     * The local DTLS parameters of {@code FakeHandler}, {@code generateLocalDtlsParameters} of the
     * tests of mediasoup-client.
     */
    public static DtlsParameters localDtlsParameters() {
        return new DtlsParameters(
                DtlsRole.AUTO,
                List.of(
                        new DtlsFingerprint(
                                FingerprintAlgorithm.SHA_256,
                                "82:5A:68:3D:36:C3:0A:DE:AF:E7:32:43:D2:88:83:57:AC:2D:65:E5:80:C4:B6:FB:AF:1A:A0:21:9F:6D:0C:AD")));
    }

    /** The parameters of a new mediasoup transport, {@code generateTransportRemoteParameters}. */
    public static TransportOptions transportRemoteParameters() {
        TransportOptions fixture = read("transport-remote-parameters", TransportOptions.class);
        return new TransportOptions(
                newId(),
                fixture.iceParameters(),
                fixture.iceCandidates(),
                fixture.dtlsParameters(),
                fixture.sctpParameters(),
                fixture.iceServers(),
                fixture.iceTransportPolicy(),
                fixture.appData());
    }

    /**
     * The parameters of a new mediasoup consumer, {@code generateConsumerRemoteParameters}.
     *
     * @param codecMimeType {@code audio/opus}, {@code audio/ISAC} or {@code video/VP8}
     */
    public static ConsumerOptions consumerRemoteParameters(String codecMimeType) {
        return consumerRemoteParameters(codecMimeType, newId());
    }

    /**
     * The parameters of a mediasoup consumer with a given id.
     *
     * @param codecMimeType {@code audio/opus}, {@code audio/ISAC} or {@code video/VP8}
     * @param id the consumer id
     */
    public static ConsumerOptions consumerRemoteParameters(String codecMimeType, String id) {
        String fixture = CONSUMER_FIXTURES.get(codecMimeType);
        if (fixture == null) {
            throw new IllegalArgumentException("unknown codecMimeType " + codecMimeType);
        }
        ConsumerOptions consumer = read(fixture, ConsumerOptions.class);
        return new ConsumerOptions(
                id, newId(), consumer.kind(), consumer.rtpParameters(), consumer.streamId(), consumer.appData());
    }

    /** The id of a new mediasoup data producer, {@code generateDataProducerRemoteParameters}. */
    public static String dataProducerRemoteParameters() {
        return newId();
    }

    /** The parameters of a new mediasoup data consumer, {@code generateDataConsumerRemoteParameters}. */
    public static DataConsumerOptions dataConsumerRemoteParameters() {
        return dataConsumerRemoteParameters(newId());
    }

    /**
     * The parameters of a mediasoup data consumer with a given id.
     *
     * @param id the data consumer id
     */
    public static DataConsumerOptions dataConsumerRemoteParameters(String id) {
        DataConsumerOptions consumer = read("data-consumer-remote-parameters", DataConsumerOptions.class);
        return new DataConsumerOptions(
                id,
                newId(),
                consumer.sctpStreamParameters(),
                consumer.label(),
                consumer.protocol(),
                consumer.appData());
    }

    /** Returns the content of a fixture file. */
    static String fixture(String name) {
        try (InputStream in = FakeParameters.class.getResourceAsStream("/fixtures/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T read(String name, Class<T> type) {
        return JSON.readValue(fixture(name), type);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
