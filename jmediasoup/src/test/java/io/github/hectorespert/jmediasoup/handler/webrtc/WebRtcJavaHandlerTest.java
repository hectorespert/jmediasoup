/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.ConnectHandler;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.internal.sdp.RemoteSdp;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsFingerprint;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IceTransportPolicy;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests {@link WebRtcJavaHandler} with the webrtc-java natives. */
class WebRtcJavaHandlerTest {

    private static final ConnectHandler CONNECT = dtls -> CompletableFuture.completedFuture(null);

    private static PeerConnectionFactory peerConnectionFactory;

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
    }

    @AfterAll
    static void disposePeerConnectionFactory() {
        peerConnectionFactory.dispose();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static Device loadedDevice(WebRtcJavaHandler.Factory factory) {
        var device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
        return device;
    }

    private static TransportOptions withRemoteDtlsRole(DtlsRole role) {
        TransportOptions options = FakeParameters.transportRemoteParameters();
        return TransportOptions.builder(
                        options.id(),
                        options.iceParameters(),
                        options.iceCandidates(),
                        new DtlsParameters(role, options.dtlsParameters().fingerprints()))
                .sctpParameters(options.sctpParameters())
                .build();
    }

    private static RTCPeerConnection peerConnection() {
        return peerConnectionFactory.createPeerConnection(new RTCConfiguration(), candidate -> {});
    }

    @Test
    void aTransportGetsAWebRtcJavaHandler() throws Exception {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        Device device = loadedDevice(factory);

        SendTransport send =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, request -> null);
        RecvTransport recv = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);

        WebRtcJavaHandler sendHandler = assertInstanceOf(WebRtcJavaHandler.class, send.handler());
        assertEquals("WebRtcJava", sendHandler.name());
        assertFalse(sendHandler.closed());
        assertInstanceOf(WebRtcJavaHandler.class, recv.handler());
        assertEquals(ConnectionState.NEW, send.connectionState());
        assertEquals(RTCPeerConnectionState.NEW, sendHandler.peerConnection().getConnectionState());

        send.close();
        recv.close();
        factory.close();
    }

    @Test
    void closingTheTransportClosesTheHandler() throws Exception {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        RecvTransport transport =
                loadedDevice(factory).createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);
        WebRtcJavaHandler handler = (WebRtcJavaHandler) transport.handler();

        transport.close();
        handler.close();

        assertTrue(handler.closed());
        assertThrows(Exception.class, handler::assertNotClosed);
        factory.close();
    }

    @Test
    void closingTheFactoryClosesItsOpenHandlers() throws Exception {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        Device device = loadedDevice(factory);
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);

        factory.close();

        assertTrue(((WebRtcJavaHandler) transport.handler()).closed());
        assertThrows(
                IllegalStateException.class,
                () -> device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT));
    }

    @Test
    void theLocalDtlsRoleIsTheOppositeOfTheRemoteOne() throws Exception {
        try (WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory)) {
            Device device = loadedDevice(factory);

            assertEquals(DtlsRole.CLIENT, localDtlsRole(device, DtlsRole.AUTO));
            assertEquals(DtlsRole.CLIENT, localDtlsRole(device, DtlsRole.SERVER));
            assertEquals(DtlsRole.SERVER, localDtlsRole(device, DtlsRole.CLIENT));
        }
    }

    private static DtlsRole localDtlsRole(Device device, DtlsRole remoteRole) throws Exception {
        RecvTransport transport = device.createRecvTransport(withRemoteDtlsRole(remoteRole), CONNECT);
        return ((WebRtcJavaHandler) transport.handler()).localDtlsRole();
    }

    @Test
    void setupTransportConnectsWithTheLocalDtlsParameters() throws Exception {
        try (WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory)) {
            var connected = new CompletableFuture<DtlsParameters>();
            SendTransport transport = loadedDevice(factory)
                    .createSendTransport(
                            FakeParameters.transportRemoteParameters(),
                            dtls -> {
                                connected.complete(dtls);
                                return CompletableFuture.completedFuture(null);
                            },
                            request -> null);
            WebRtcJavaHandler handler = (WebRtcJavaHandler) transport.handler();
            RTCPeerConnection pc = handler.peerConnection();
            var init = new RTCRtpTransceiverInit();
            init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
            pc.addTransceiver(MediaType.AUDIO, init);
            RTCSessionDescription offer = await(PeerConnections.createOffer(pc, new RTCOfferOptions()));

            await(handler.setupTransport(DtlsRole.SERVER, Parser.parse(offer.sdp)));

            DtlsParameters dtls = await(connected);
            assertEquals(DtlsRole.SERVER, dtls.role());
            assertFalse(dtls.fingerprints().isEmpty());
            for (DtlsFingerprint fingerprint : dtls.fingerprints()) {
                assertTrue(offer.sdp.contains(fingerprint.value()), fingerprint.toString());
            }
            assertTrue(handler.transportReady());
        }
    }

    @Test
    void setupTransportFailsWithoutALocalDescription() throws Exception {
        try (WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory)) {
            RecvTransport transport =
                    loadedDevice(factory).createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);
            WebRtcJavaHandler handler = (WebRtcJavaHandler) transport.handler();

            CompletionException failure =
                    assertThrows(CompletionException.class, () -> await(handler.setupTransport(DtlsRole.CLIENT, null)));

            assertInstanceOf(MediasoupClientException.class, failure.getCause());
            assertFalse(handler.transportReady());
        }
    }

    // 5.1.15: webrtc-java accepts the offers of RemoteSdp for receiving.
    @Test
    void webrtcJavaAcceptsTheRemoteOffersForReceiving() throws Exception {
        TransportOptions transport = FakeParameters.transportRemoteParameters();
        var remoteSdp = new RemoteSdp(transport.iceParameters(), transport.iceCandidates(), transport.dtlsParameters());
        remoteSdp.receive(
                "0",
                "audio",
                FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters(),
                "stream",
                "audio-track");
        remoteSdp.receive(
                "1",
                "video",
                FakeParameters.consumerRemoteParameters("video/VP8").rtpParameters(),
                "stream",
                "video-track");
        RTCPeerConnection pc = peerConnection();
        try {
            await(PeerConnections.setRemoteDescription(
                    pc, new RTCSessionDescription(RTCSdpType.OFFER, remoteSdp.getSdp())));
            RTCSessionDescription answer = await(PeerConnections.createAnswer(pc));
            await(PeerConnections.setLocalDescription(pc, answer));

            assertEquals(2, pc.getTransceivers().length);
            assertEquals(
                    "audio-track",
                    pc.getTransceivers()[0].getReceiver().getTrack().getId());
        } finally {
            pc.close();
        }
    }

    // 5.1.15: webrtc-java accepts the answers of RemoteSdp for sending.
    @Test
    void webrtcJavaAcceptsTheRemoteAnswersForSending() throws Exception {
        TransportOptions transport = FakeParameters.transportRemoteParameters();
        var remoteSdp = new RemoteSdp(transport.iceParameters(), transport.iceCandidates(), transport.dtlsParameters());
        // As setupTransport does before the first send, with the local side as DTLS client.
        remoteSdp.updateDtlsRole(DtlsRole.SERVER);
        RTCPeerConnection pc = peerConnection();
        try {
            for (MediaType type : List.of(MediaType.AUDIO, MediaType.VIDEO)) {
                var init = new RTCRtpTransceiverInit();
                init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
                pc.addTransceiver(type, init);
            }
            RTCSessionDescription offer = await(PeerConnections.createOffer(pc, new RTCOfferOptions()));
            await(PeerConnections.setLocalDescription(pc, offer));

            RtpCapabilities nativeCapabilities = await(WebRtcJavaHandler.createFactory(peerConnectionFactory)
                    .getNativeRtpCapabilities(io.github.hectorespert.jmediasoup.transport.TransportDirection.SEND));
            var extended =
                    Ortc.getExtendedRtpCapabilities(nativeCapabilities, FakeParameters.routerRtpCapabilities(), false);
            Map<String, Object> localSdpObject = Parser.parse(offer.sdp);
            List<Map<String, Object>> media = mediaSections(localSdpObject);
            for (Map<String, Object> offerMediaObject : media) {
                MediaKind kind = MediaKind.fromValue((String) offerMediaObject.get("type"));
                RtpParameters answerRtpParameters = Ortc.getSendingRemoteRtpParameters(kind, extended);
                remoteSdp.send(offerMediaObject, null, answerRtpParameters);
            }

            await(PeerConnections.setRemoteDescription(
                    pc, new RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp.getSdp())));

            assertEquals(dev.onvoid.webrtc.RTCSignalingState.STABLE, pc.getSignalingState());
        } finally {
            pc.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mediaSections(Map<String, Object> sdpObject) {
        return (List<Map<String, Object>>) sdpObject.get("media");
    }

    @Test
    void iceServersAndPolicyAreConverted() {
        List<RTCIceServer> servers = WebRtcJavaHandler.toRtcIceServers(List.of(
                new IceServer(List.of("turn:turn.example.com"), "user", "secret"),
                new IceServer(List.of("stun:stun.example.com"), null, null)));

        assertEquals(List.of("turn:turn.example.com"), servers.get(0).urls);
        assertEquals("user", servers.get(0).username);
        assertEquals("secret", servers.get(0).password);
        assertEquals(List.of("stun:stun.example.com"), servers.get(1).urls);
        assertEquals(RTCIceTransportPolicy.ALL, WebRtcJavaHandler.toRtcIceTransportPolicy(null));
        assertEquals(RTCIceTransportPolicy.ALL, WebRtcJavaHandler.toRtcIceTransportPolicy(IceTransportPolicy.ALL));
        assertEquals(RTCIceTransportPolicy.RELAY, WebRtcJavaHandler.toRtcIceTransportPolicy(IceTransportPolicy.RELAY));
    }

    @Test
    void statesAreConverted() {
        for (RTCPeerConnectionState state : RTCPeerConnectionState.values()) {
            assertEquals(
                    state.name(), WebRtcJavaHandler.toConnectionState(state).name());
        }
        for (RTCIceGatheringState state : RTCIceGatheringState.values()) {
            assertEquals(
                    state.name(), WebRtcJavaHandler.toIceGatheringState(state).name());
        }
        assertSame(ConnectionState.CONNECTED, WebRtcJavaHandler.toConnectionState(RTCPeerConnectionState.CONNECTED));
        assertSame(IceGatheringState.COMPLETE, WebRtcJavaHandler.toIceGatheringState(RTCIceGatheringState.COMPLETE));
    }
}
