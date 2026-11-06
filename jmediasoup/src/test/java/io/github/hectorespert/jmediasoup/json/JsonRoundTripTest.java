/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.ConsumerOptions;
import io.github.hectorespert.jmediasoup.DataConsumerOptions;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.Priority;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionDirection;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceCandidateTcpType;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IpVersion;
import io.github.hectorespert.jmediasoup.transport.PlainRtpParameters;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;

class JsonRoundTripTest {

    // The router RTP capabilities of mediasoup 3.19, trimmed, with every list present so that the
    // round trip must give back the same tree.
    private static final String ROUTER_CAPABILITIES = """
            {
              "codecs": [
                {"kind": "audio", "mimeType": "audio/opus", "preferredPayloadType": 100, "clockRate": 48000,
                 "channels": 2, "parameters": {}, "rtcpFeedback": [{"type": "nack"}, {"type": "transport-cc"}]},
                {"kind": "video", "mimeType": "video/VP8", "preferredPayloadType": 101, "clockRate": 90000,
                 "parameters": {}, "rtcpFeedback": [{"type": "nack"}, {"type": "nack", "parameter": "pli"},
                 {"type": "ccm", "parameter": "fir"}, {"type": "goog-remb"}, {"type": "transport-cc"}]},
                {"kind": "video", "mimeType": "video/rtx", "preferredPayloadType": 102, "clockRate": 90000,
                 "parameters": {"apt": 101}, "rtcpFeedback": []},
                {"kind": "video", "mimeType": "video/H264", "preferredPayloadType": 107, "clockRate": 90000,
                 "parameters": {"level-asymmetry-allowed": 1, "packetization-mode": 1, "profile-level-id": "4d0032"},
                 "rtcpFeedback": [{"type": "nack"}]}
              ],
              "headerExtensions": [
                {"kind": "audio", "uri": "urn:ietf:params:rtp-hdrext:sdes:mid", "preferredId": 1,
                 "preferredEncrypt": false, "direction": "sendrecv"},
                {"kind": "video", "uri": "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
                 "preferredId": 4, "preferredEncrypt": false, "direction": "sendrecv"},
                {"kind": "video", "uri": "urn:3gpp:video-orientation", "preferredId": 11,
                 "preferredEncrypt": false, "direction": "recvonly"}
              ],
              "fecMechanisms": []
            }
            """;

    // A createWebRtcTransport response of the demo server, with ip and address in each candidate.
    private static final String TRANSPORT = """
            {
              "id": "c6d4b5a2-transport",
              "iceParameters": {"usernameFragment": "abc", "password": "def", "iceLite": true},
              "iceCandidates": [
                {"foundation": "udpcandidate", "priority": 1076302079, "ip": "127.0.0.1", "address": "127.0.0.1",
                 "protocol": "udp", "port": 40200, "type": "host"},
                {"foundation": "tcpcandidate", "priority": 1076276479, "ip": "127.0.0.1", "address": "127.0.0.1",
                 "protocol": "tcp", "port": 40201, "type": "host", "tcpType": "passive"}
              ],
              "dtlsParameters": {"role": "auto", "fingerprints": [{"algorithm": "sha-256", "value": "AB:CD"}]},
              "sctpParameters": {"port": 5000, "OS": 1024, "MIS": 1024, "maxMessageSize": 262144},
              "iceServers": [],
              "appData": {}
            }
            """;

    @WithEachJackson
    void routerCapabilitiesSurviveTheRoundTrip(JsonCodec json) throws Exception {
        RtpCapabilities capabilities = json.read(ROUTER_CAPABILITIES, RtpCapabilities.class);

        assertEquals(4, capabilities.codecs().size());
        assertEquals(
                RtpHeaderExtensionDirection.RECVONLY,
                capabilities.headerExtensions().get(2).direction());
        assertEquals(json.tree(ROUTER_CAPABILITIES), json.tree(json.write(capabilities)));
        assertEquals(capabilities, json.read(json.write(capabilities), RtpCapabilities.class));
    }

    @WithEachJackson
    void codecParametersKeepTheirJsonTypesAndOrder(JsonCodec json) throws Exception {
        RtpCapabilities capabilities = json.read(ROUTER_CAPABILITIES, RtpCapabilities.class);
        Map<String, Object> rtx = capabilities.codecs().get(2).parameters();
        Map<String, Object> h264 = capabilities.codecs().get(3).parameters();

        assertInstanceOf(Integer.class, rtx.get("apt"));
        assertInstanceOf(String.class, h264.get("profile-level-id"));
        assertEquals(
                List.of("level-asymmetry-allowed", "packetization-mode", "profile-level-id"),
                List.copyOf(h264.keySet()));
        assertTrue(json.write(capabilities).contains("\"apt\":101"));
    }

    @WithEachJackson
    void transportResponseIsReadAsTransportOptions(JsonCodec json) throws Exception {
        TransportOptions options = json.read(TRANSPORT, TransportOptions.class);

        assertEquals("127.0.0.1", options.iceCandidates().get(1).effectiveAddress());
        assertEquals(IceCandidateTcpType.PASSIVE, options.iceCandidates().get(1).tcpType());
        assertEquals(DtlsRole.AUTO, options.dtlsParameters().role());
        assertEquals(1024, options.sctpParameters().os());
        assertEquals(json.tree(TRANSPORT), json.tree(json.write(options)));
    }

    @WithEachJackson
    void enumsAndRenamedFieldsUseTheMediasoupNames(JsonCodec json) throws Exception {
        String streams = json.write(new SctpCapabilities(new NumSctpStreams(1024, 2048)));
        String encoding = json.write(RtpEncodingParameters.builder()
                .rid("r0")
                .priority(Priority.VERY_LOW)
                .build());

        assertEquals(json.tree("{\"numStreams\": {\"OS\": 1024, \"MIS\": 2048}}"), json.tree(streams));
        assertEquals(json.tree("{\"rid\": \"r0\", \"priority\": \"very-low\"}"), json.tree(encoding));
        assertEquals(
                json.tree("{\"ip\": \"10.0.0.1\", \"ipVersion\": 4, \"port\": 5000}"),
                json.tree(json.write(new PlainRtpParameters("10.0.0.1", IpVersion.IPV4, 5000))));
        assertEquals(MediaKind.VIDEO, json.read("\"video\"", MediaKind.class));
    }

    @WithEachJackson
    void absentFieldsAreNotWritten(JsonCodec json) throws Exception {
        String codec = json.write(new RtpCodecParameters("audio/opus", 100, 48000, null, null, null));

        assertFalse(codec.contains("channels"));
        assertFalse(codec.contains("null"));
    }

    @WithEachJackson
    void unknownFieldsAreIgnored(JsonCodec json) throws Exception {
        RtpCodecParameters codec = json.read(
                "{\"mimeType\": \"audio/opus\", \"payloadType\": 100, \"clockRate\": 48000, \"futureField\": [1, 2]}",
                RtpCodecParameters.class);

        assertEquals(100, codec.payloadType());
        assertNull(codec.channels());
    }

    @WithEachJackson
    void unknownEnumValuesFail(JsonCodec json) {
        String candidate = TRANSPORT.replace("\"type\": \"host\", \"tcpType\"", "\"type\": \"xyz\", \"tcpType\"");

        Exception error = assertThrows(Exception.class, () -> json.read(candidate, TransportOptions.class));

        assertTrue(rootCause(error).getMessage().contains("Unknown ICE candidate type: xyz"), error::toString);
    }

    @WithEachJackson
    void missingRequiredNumbersFail(JsonCodec json) {
        // Without required = true, a missing payload type would silently become 0, a valid value.
        assertThrows(
                Exception.class,
                () -> json.read("{\"mimeType\": \"audio/opus\", \"clockRate\": 48000}", RtpCodecParameters.class));
    }

    @WithEachJackson
    void invalidValuesFailWithTheConstructorMessage(JsonCodec json) {
        Exception error = assertThrows(
                Exception.class,
                () -> json.read(
                        "{\"mimeType\": \"opus\", \"payloadType\": 100, \"clockRate\": 48000}",
                        RtpCodecParameters.class));

        assertTrue(rootCause(error).getMessage().startsWith("mimeType must be"), error::toString);
    }

    @WithEachJackson
    void iceServerUrlsMayBeASingleText(JsonCodec json) throws Exception {
        IceServer server = json.read(
                "{\"urls\": \"turn:turn.example.com\", \"username\": \"u\", \"credential\": \"p\"}", IceServer.class);

        assertEquals(List.of("turn:turn.example.com"), server.urls());
    }

    @WithEachJackson
    void appDataKeepsNullsAndNestedValues(JsonCodec json) throws Exception {
        String response = """
                {"id": "consumer-1", "producerId": "producer-1", "kind": "audio",
                 "rtpParameters": {"codecs": [{"mimeType": "audio/opus", "payloadType": 100, "clockRate": 48000}]},
                 "appData": {"peer": null, "tags": ["a", "b"], "room": {"id": 7}}}
                """;

        ConsumerOptions options = json.read(response, ConsumerOptions.class);

        assertTrue(options.appData().containsKey("peer"));
        assertNull(options.appData().get("peer"));
        assertEquals(List.of("a", "b"), options.appData().get("tags"));
        assertEquals(options, json.read(json.write(options), ConsumerOptions.class));
    }

    @WithEachJackson
    void dataConsumerResponseIsReadAsOptions(JsonCodec json) throws Exception {
        DataConsumerOptions options = json.read("""
                {"id": "dc-1", "dataProducerId": "dp-1", "label": "chat", "protocol": "",
                 "sctpStreamParameters": {"streamId": 3, "ordered": true}}
                """, DataConsumerOptions.class);

        assertEquals(3, options.sctpStreamParameters().streamId());
        assertEquals("chat", options.label());
    }

    @WithEachJackson
    void codecCapabilityKeepsAnAbsentPayloadTypeAbsent(JsonCodec json) throws Exception {
        RtpCodecCapability codec = json.read(
                "{\"kind\": \"audio\", \"mimeType\": \"audio/PCMU\", \"clockRate\": 8000}", RtpCodecCapability.class);

        assertNull(codec.preferredPayloadType());
        assertEquals(
                json.tree(
                        "{\"kind\": \"audio\", \"mimeType\": \"audio/PCMU\", \"clockRate\": 8000, \"parameters\": {}, \"rtcpFeedback\": []}"),
                json.tree(json.write(codec)));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
