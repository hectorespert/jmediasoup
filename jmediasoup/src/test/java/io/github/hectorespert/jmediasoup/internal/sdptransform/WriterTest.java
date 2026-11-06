/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdptransform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WriterTest {

    private static Map<String, Object> session(Object... keysAndValues) {
        var session = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            session.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        session.putIfAbsent("media", new ArrayList<>());
        return session;
    }

    // From the iceliteSdp test of parse.test.js.
    @Test
    void iceLiteIsWrittenAtSessionLevel() {
        String rew = Writer.write(ParserTest.parse("icelite.sdp"));

        assertTrue(rew.contains("a=ice-lite\r\n"), "got ice-lite");
        assertTrue(rew.indexOf("m=") > rew.indexOf("a=ice-lite"), "session level icelite");
    }

    // From the jsepSdp test of parse.test.js.
    @Test
    void endOfCandidatesIsWrittenAfterTheCandidates() {
        List<String> rewritten =
                Arrays.asList(Writer.write(ParserTest.parse("jsep.sdp")).split("\r\n", -1));

        int idx = rewritten.indexOf("a=end-of-candidates");
        assertTrue(rewritten.get(idx - 1).startsWith("a=candidate"), "marker after candidate");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "webrtc-java-0.20.0/offer-datachannel.sdp",
                "webrtc-java-0.20.0/offer-native-capabilities.sdp",
                "webrtc-java-0.20.0/offer-recvonly-no-tracks.sdp",
                "webrtc-java-0.20.0/offer-sendonly.sdp",
                "webrtc-java-0.20.0/offer-simulcast.sdp"
            })
    void anOfferOfWebrtcJavaKeepsAllItsLines(String file) {
        String sdp = ParserTest.read(file);

        String written = Writer.write(Parser.parse(sdp));

        // Like in the original, a=msid-semantic without a token reads as an empty semantic and the
        // token WMS, so it is written back with two spaces.
        assertEquals(sorted(sdp.replace("a=msid-semantic: WMS\r\n", "a=msid-semantic:  WMS\r\n")), sorted(written));
        assertTrue(written.endsWith("\r\n"));
    }

    private static List<String> sorted(String sdp) {
        return Arrays.stream(sdp.split("\r\n|\r|\n")).sorted().toList();
    }

    @Test
    void theRequiredLinesAreAdded() {
        Map<String, Object> session = session(
                "media",
                new ArrayList<>(List.of(
                        new LinkedHashMap<String, Object>(Map.of("type", "audio", "port", 9, "protocol", "RTP/AVP")))));

        assertEquals("v=0\r\ns= \r\nm=audio 9 RTP/AVP \r\n", Writer.write(session));
        assertEquals(0, session.get("version"));
        assertEquals(" ", session.get("name"));
        assertEquals("", ParserTest.at(session, "media", 0, "payloads"));
    }

    @Test
    void sessionLinesFollowTheRfcOrderAndMediaLinesFollowTheirOwn() {
        Map<String, Object> session = Parser.parse(String.join(
                "\r\n",
                "a=ice-lite",
                "t=0 0",
                "s=-",
                "o=- 1 2 IN IP4 127.0.0.1",
                "v=0",
                "m=audio 9 RTP/AVP 0",
                "a=mid:0",
                "c=IN IP4 0.0.0.0",
                ""));

        assertEquals(
                String.join(
                        "\r\n",
                        "v=0",
                        "o=- 1 2 IN IP4 127.0.0.1",
                        "s=-",
                        "t=0 0",
                        "a=ice-lite",
                        "m=audio 9 RTP/AVP 0",
                        "c=IN IP4 0.0.0.0",
                        "a=mid:0",
                        ""),
                Writer.write(session));
    }

    @Test
    void theOrderOfTypesCanBeChosen() {
        Map<String, Object> session =
                Parser.parse("v=0\r\ns=-\r\nt=0 0\r\nm=audio 9 RTP/AVP 0\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\n");

        assertEquals(
                "t=0 0\r\nv=0\r\nm=audio 9 RTP/AVP 0\r\na=mid:0\r\nc=IN IP4 0.0.0.0\r\n",
                Writer.write(session, List.of('t', 'v'), List.of('a', 'c')));
    }

    @Test
    void optionalPartsOfALineAreLeftOut() {
        Map<String, Object> session = Parser.parse(String.join(
                "\r\n",
                "v=0",
                "m=video 9 UDP/TLS/RTP/SAVPF 96",
                "a=rtpmap:96 VP8/90000",
                "a=rtcp:9",
                "a=rtcp-fb:96 nack",
                "a=rtcp-fb:96 nack pli",
                "a=extmap:1/sendonly urn:ietf:params:rtp-hdrext:encrypt URI-toffset",
                "a=extmap:2 URI-gps-string",
                "a=candidate:1 1 udp 2 10.0.0.1 9 typ host",
                "a=candidate:2 1 tcp 3 10.0.0.2 9 typ srflx raddr 10.0.0.1 rport 9 tcptype active generation 0",
                "a=ssrc:1 cname:abc",
                "a=ssrc:1 foo",
                ""));

        String written = Writer.write(session);

        for (String line : List.of(
                "a=rtpmap:96 VP8/90000",
                "a=rtcp:9",
                "a=rtcp-fb:96 nack",
                "a=rtcp-fb:96 nack pli",
                "a=extmap:1/sendonly urn:ietf:params:rtp-hdrext:encrypt URI-toffset",
                "a=extmap:2 URI-gps-string",
                "a=candidate:1 1 udp 2 10.0.0.1 9 typ host",
                "a=candidate:2 1 tcp 3 10.0.0.2 9 typ srflx raddr 10.0.0.1 rport 9 tcptype active generation 0",
                "a=ssrc:1 cname:abc",
                "a=ssrc:1 foo")) {
            assertTrue(written.contains("\r\n" + line + "\r\n"), line);
        }
    }

    @Test
    void valuesAreWrittenLikeJavaScriptDoes() {
        var mLine = new LinkedHashMap<String, Object>(Map.of("type", "audio", "port", 9, "protocol", "RTP/AVP"));
        mLine.put("ptime", 0.125);
        mLine.put("maxptime", "60");
        mLine.put("framerate", 30.0);
        mLine.put("msid", List.of(Map.of("id", "stream")));
        mLine.put("ssrcs", List.of(Map.of("id", 4294967295L, "attribute", "cname", "value", "x")));
        mLine.put("rtcpFbTrrInt", List.of(Map.of("payload", "*", "value", "abc")));
        Map<String, Object> session = session("media", List.of(mLine));

        String written = Writer.write(session);

        // %d converts with Number(): texts become numbers and non-numbers NaN.
        assertTrue(written.contains("a=ptime:0.125\r\n"), written);
        assertTrue(written.contains("a=maxptime:60\r\n"), written);
        assertTrue(written.contains("a=rtcp-fb:* trr-int NaN\r\n"), written);
        // %s writes numbers as JavaScript does, and a missing value as undefined.
        assertTrue(written.contains("a=framerate:30\r\n"), written);
        assertTrue(written.contains("a=msid:stream undefined\r\n"), written);
        assertTrue(written.contains("a=ssrc:4294967295 cname:x\r\n"), written);
    }

    @Test
    void numbersAreWrittenLikeJavaScript() {
        assertEquals("1", JavaScript.numberToString(1.0));
        assertEquals("0", JavaScript.numberToString(-0.0));
        assertEquals("29.97", JavaScript.numberToString(29.97));
        assertEquals("0.000001", JavaScript.numberToString(1e-6));
        assertEquals("1e-7", JavaScript.numberToString(1e-7));
        assertEquals("-1.5e-7", JavaScript.numberToString(-1.5e-7));
        assertEquals("100000000000000000000", JavaScript.numberToString(1e20));
        assertEquals("1e+21", JavaScript.numberToString(1e21));
        assertEquals("1.2345e+25", JavaScript.numberToString(1.2345e25));
        assertEquals("NaN", JavaScript.numberToString(Double.NaN));
        assertEquals("-Infinity", JavaScript.numberToString(Double.NEGATIVE_INFINITY));
        assertEquals("1,,b", JavaScript.string(Arrays.asList(1, null, "b")));
        assertEquals("[object Object]", JavaScript.string(Map.of()));
        assertEquals("true", JavaScript.string(true));
    }
}
