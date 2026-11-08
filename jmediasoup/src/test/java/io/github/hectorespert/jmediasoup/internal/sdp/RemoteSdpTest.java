/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteSdpTest {

    private static final TransportOptions TRANSPORT = FakeParameters.transportRemoteParameters();
    private static final String DEPENDENCY_DESCRIPTOR =
            "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension";

    private static RemoteSdp remoteSdp() {
        return new RemoteSdp(TRANSPORT.iceParameters(), TRANSPORT.iceCandidates(), TRANSPORT.dtlsParameters());
    }

    private static RtpParameters consumer(String mimeType) {
        return FakeParameters.consumerRemoteParameters(mimeType).rtpParameters();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> media(String sdp) {
        return (List<Map<String, Object>>) Parser.parse(sdp).get("media");
    }

    private static String bundle(String sdp) {
        return sdp.lines()
                .filter(line -> line.startsWith("a=group:BUNDLE"))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> offerSection(String file, int index) {
        return ((List<Map<String, Object>>) CommonUtilsTest.offer(file).get("media")).get(index);
    }

    private static RtpParameters answer(String mimeType, int payloadType) {
        return new RtpParameters(
                null,
                List.of(new RtpCodecParameters(mimeType, payloadType, 90000, null, null, null)),
                List.of(
                        new RtpHeaderExtensionParameters("urn:ietf:params:rtp-hdrext:sdes:mid", 9, null, null),
                        new RtpHeaderExtensionParameters(DEPENDENCY_DESCRIPTOR, 13, null, null)),
                null,
                null,
                null);
    }

    @Test
    void anEmptySdpHasTheSessionLines() {
        String username = "jmediasoup-v"
                + RemoteSdp.class.getModule().getDescriptor().rawVersion().orElseThrow();

        assertEquals(
                String.join(
                        "\r\n",
                        "v=0",
                        "o=" + username + " 10000 1 IN IP4 0.0.0.0",
                        "s=-",
                        "t=0 0",
                        "a=ice-lite",
                        "a=fingerprint:sha-512 84:27:A4:28:A4:73:AF:43:02:2A:44:68:FF:2F:29:5C:3B:11:9A:60:F4:A8:F0:F5:"
                                + "AC:A0:E3:49:3E:B1:34:53:A9:85:CE:51:9B:ED:87:5E:B8:F4:8E:3D:FA:20:51:B8:96:EE:DA:56:"
                                + "DC:2F:5C:62:79:15:23:E0:21:82:2B:2C",
                        "a=ice-options:ice2",
                        "a=msid-semantic: WMS *",
                        "a=group:BUNDLE ",
                        ""),
                remoteSdp().getSdp());
    }

    @Test
    void eachSdpHasANewVersion() {
        RemoteSdp remoteSdp = remoteSdp();

        assertTrue(remoteSdp.getSdp().contains(" 10000 1 IN IP4 "));
        assertTrue(remoteSdp.getSdp().contains(" 10000 2 IN IP4 "));
    }

    @Test
    void withoutDtlsThereIsNoBundle() {
        String sdp = new RemoteSdp(new IceParameters("u", "p", false), null, null).getSdp();

        assertFalse(sdp.contains("a=ice-lite"), sdp);
        assertFalse(sdp.contains("a=fingerprint"), sdp);
        assertFalse(sdp.contains("a=group"), sdp);
        assertFalse(sdp.contains("a=msid-semantic"), sdp);
    }

    @Test
    void receivingAddsSectionsToTheBundle() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();

        remoteSdp.receive("0", "audio", consumer("audio/opus"), "stream", "audio-track");
        remoteSdp.receive("1", "video", consumer("video/VP8"), "stream", "video-track");
        String sdp = remoteSdp.getSdp();

        assertEquals("a=group:BUNDLE 0 1", bundle(sdp));
        assertTrue(sdp.contains("\r\nt=0 0\r\na=extmap-allow-mixed\r\n"), sdp);
        assertEquals(
                List.of("audio", "video"),
                media(sdp).stream().map(m -> m.get("type")).toList());
        assertEquals(new RemoteSdp.MediaSectionIdx(2, null), remoteSdp.getNextMediaSectionIdx());
    }

    @Test
    void closedSectionsLeaveTheBundleAndAreRecycledByKind() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.receive("0", "audio", consumer("audio/opus"), "stream", "a0");
        remoteSdp.receive("1", "video", consumer("video/VP8"), "stream", "v1");
        remoteSdp.receive("2", "audio", consumer("audio/opus"), "stream", "a2");

        assertTrue(remoteSdp.closeMediaSection("1"));
        assertEquals("a=group:BUNDLE 0 2", bundle(remoteSdp.getSdp()));
        assertEquals(new RemoteSdp.MediaSectionIdx(1, "1"), remoteSdp.getNextMediaSectionIdx());

        // An audio section cannot reuse the closed video one.
        remoteSdp.receive("3", "audio", consumer("audio/opus"), "stream", "a3");
        assertEquals("a=group:BUNDLE 0 2 3", bundle(remoteSdp.getSdp()));

        remoteSdp.receive("4", "video", consumer("video/VP8"), "stream", "v4");
        String sdp = remoteSdp.getSdp();
        assertEquals("a=group:BUNDLE 0 4 2 3", bundle(sdp));
        assertEquals(
                List.of("0", "4", "2", "3"),
                media(sdp).stream().map(m -> m.get("mid")).map(String::valueOf).toList());
        assertThrows(MediasoupClientException.class, () -> remoteSdp.disableMediaSection("1"));
    }

    @Test
    void theFirstSectionIsOnlyDisabled() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.receive("0", "audio", consumer("audio/opus"), "stream", "a0");

        assertFalse(remoteSdp.closeMediaSection("0"));

        Map<String, Object> section = media(remoteSdp.getSdp()).getFirst();
        assertEquals("inactive", section.get("direction"));
        assertEquals(7, section.get("port"));
        assertEquals("a=group:BUNDLE 0", bundle(remoteSdp.getSdp()));
    }

    @Test
    void sectionsCanBePausedAndResumed() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.receive("0", "audio", consumer("audio/opus"), "stream", "a0");

        remoteSdp.pauseMediaSection("0");
        assertEquals("inactive", media(remoteSdp.getSdp()).getFirst().get("direction"));

        remoteSdp.resumeReceivingMediaSection("0");
        assertEquals("sendonly", media(remoteSdp.getSdp()).getFirst().get("direction"));

        remoteSdp.disableMediaSection("0");
        assertEquals("inactive", media(remoteSdp.getSdp()).getFirst().get("direction"));
    }

    @Test
    void sendingAnswersTheSectionsOfTheOffer() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        Map<String, Object> audio = offerSection("offer-sendonly.sdp", 0);

        remoteSdp.send(audio, null, answer("audio/opus", 111));
        remoteSdp.send(offerSection("offer-sendonly.sdp", 1), null, answer("video/VP8", 96));
        String sdp = remoteSdp.getSdp();

        assertEquals("a=group:BUNDLE 0 1", bundle(sdp));
        assertEquals(
                List.of("recvonly", "recvonly"),
                media(sdp).stream().map(m -> m.get("direction")).toList());

        // A section with an existing MID replaces it, as Plan-B does with the same kind.
        remoteSdp.send(audio, null, answer("audio/PCMU", 0));
        assertEquals(
                "PCMU",
                CommonUtils.list(media(remoteSdp.getSdp()).getFirst().get("rtp"))
                        .getFirst()
                        .get("codec"));
    }

    @Test
    void sendingWithCodecOptionsReturnsTheChangedOffer() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        RtpParameters offer = answer("audio/opus", 111);

        RtpParameters changed = remoteSdp.send(
                offerSection("offer-sendonly.sdp", 0),
                null,
                offer,
                offer,
                ProducerCodecOptions.builder().opusStereo(true).build());

        assertEquals(Map.of("sprop-stereo", 1), changed.codecs().getFirst().parameters());
        assertEquals(offer.headerExtensions(), changed.headerExtensions());
        assertTrue(remoteSdp.getSdp().contains("a=fmtp:111 stereo=1\r\n"));
    }

    @Test
    void sendingCanReuseAClosedSection() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.send(offerSection("offer-sendonly.sdp", 0), null, answer("audio/opus", 111));
        remoteSdp.send(offerSection("offer-sendonly.sdp", 1), null, answer("video/VP8", 96));
        remoteSdp.closeMediaSection("1");

        Map<String, Object> reused = offerSection("offer-sendonly.sdp", 1);
        reused.put("mid", 2);
        remoteSdp.send(reused, "1", answer("video/VP8", 96));

        assertEquals("a=group:BUNDLE 0 2", bundle(remoteSdp.getSdp()));
        assertThrows(
                MediasoupClientException.class,
                () -> remoteSdp.send(offerSection("offer-sendonly.sdp", 1), "9", answer("video/VP8", 96)));
    }

    @Test
    void theDependencyDescriptorIsOnlyKeptForAv1AndH264() throws Exception {
        Map<String, Object> offer = offerSection("offer-simulcast.sdp", 0);

        RemoteSdp vp8 = remoteSdp();
        vp8.send(offer, null, answer("video/VP8", 96));
        RemoteSdp h264 = remoteSdp();
        h264.send(offer, null, answer("video/H264", 102));

        assertFalse(vp8.getSdp().contains(DEPENDENCY_DESCRIPTOR));
        assertTrue(h264.getSdp().contains("a=extmap:13 " + DEPENDENCY_DESCRIPTOR + "\r\n"));
    }

    @Test
    void newIceParametersApplyToEverySection() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.receive("0", "audio", consumer("audio/opus"), "stream", "a0");

        remoteSdp.updateIceParameters(new IceParameters("ufrag2", "pwd2", false));
        String sdp = remoteSdp.getSdp();

        assertFalse(sdp.contains("a=ice-lite"), sdp);
        assertEquals("ufrag2", media(sdp).getFirst().get("iceUfrag"));
        assertEquals("pwd2", media(sdp).getFirst().get("icePwd"));
    }

    @Test
    void theDtlsRoleAppliesToEverySection() throws Exception {
        RemoteSdp remoteSdp = remoteSdp();
        remoteSdp.send(offerSection("offer-sendonly.sdp", 0), null, answer("audio/opus", 111));
        assertEquals("actpass", media(remoteSdp.getSdp()).getFirst().get("setup"));

        remoteSdp.updateDtlsRole(DtlsRole.CLIENT);

        assertEquals("active", media(remoteSdp.getSdp()).getFirst().get("setup"));
    }

    private static RemoteSdp sctpRemoteSdp() {
        return new RemoteSdp(
                TRANSPORT.iceParameters(),
                TRANSPORT.iceCandidates(),
                TRANSPORT.dtlsParameters(),
                TRANSPORT.sctpParameters());
    }

    @Test
    void sendSctpAssociationAnswersTheApplicationSectionOfTheOffer() {
        RemoteSdp remoteSdp = sctpRemoteSdp();

        remoteSdp.sendSctpAssociation(offerSection("offer-datachannel.sdp", 0));
        String sdp = remoteSdp.getSdp();

        Map<String, Object> application = media(sdp).getFirst();
        assertEquals("application", application.get("type"));
        assertEquals("UDP/DTLS/SCTP", application.get("protocol"));
        assertEquals("webrtc-datachannel", application.get("payloads"));
        assertEquals(5000, application.get("sctpPort"));
        assertEquals(2000000, application.get("maxMessageSize"));
        assertEquals("0", String.valueOf(application.get("mid")));
        assertEquals("a=group:BUNDLE 0", bundle(sdp));
    }

    @Test
    void receiveSctpAssociationOffersTheApplicationSection() {
        RemoteSdp remoteSdp = sctpRemoteSdp();

        remoteSdp.receiveSctpAssociation(false);
        String sdp = remoteSdp.getSdp();

        Map<String, Object> application = media(sdp).getFirst();
        assertEquals("application", application.get("type"));
        assertEquals("UDP/DTLS/SCTP", application.get("protocol"));
        assertEquals("webrtc-datachannel", application.get("payloads"));
        assertEquals(5000, application.get("sctpPort"));
        assertEquals("datachannel", application.get("mid"));
        assertEquals("actpass", application.get("setup"));
        assertEquals("a=group:BUNDLE datachannel", bundle(sdp));
    }

    @Test
    void theSctpAssociationNeedsSctpParameters() {
        RemoteSdp remoteSdp = remoteSdp();

        assertThrows(NullPointerException.class, () -> remoteSdp.receiveSctpAssociation(false));
        assertThrows(
                NullPointerException.class,
                () -> remoteSdp.sendSctpAssociation(offerSection("offer-datachannel.sdp", 0)));
    }
}
