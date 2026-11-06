/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static io.github.hectorespert.jmediasoup.internal.sdp.ParserTest.at;
import static io.github.hectorespert.jmediasoup.internal.sdp.ParserTest.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cases of the parser that the tests of sdp-transform do not cover. */
class ParserValuesTest {

    @Test
    void fieldsThatReadTheSameAsANumberAreNumbersOfTheSmallestType() {
        assertEquals(0, Parser.toIntIfInt("0"));
        assertEquals(-1, Parser.toIntIfInt("-1"));
        assertEquals(2147483647, Parser.toIntIfInt("2147483647"));
        assertEquals(2147483648L, Parser.toIntIfInt("2147483648"));
        assertEquals(3735928559L, Parser.toIntIfInt("3735928559"));
        assertEquals(9007199254740992L, Parser.toIntIfInt("9007199254740992"));
        assertEquals(new BigInteger("10000000000000000000"), Parser.toIntIfInt("10000000000000000000"));
        assertEquals(29.97, Parser.toIntIfInt("29.97"));
        assertEquals(0.125, Parser.toIntIfInt("0.125"));
        assertEquals(-0.5, Parser.toIntIfInt("-0.5"));
        assertEquals(0.000001, Parser.toIntIfInt("0.000001"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "007",
                "-0",
                "+1",
                "1.0",
                "1.50",
                ".5",
                "1.",
                "1e3",
                "0x10",
                " 1",
                "1 ",
                "NaN",
                "Infinity",
                "0.0000001",
                "9007199254740993",
                "3710604898417546434",
                "100000000000000000000000",
                "0.1000000000000000055511151231257827",
                "42e01f"
            })
    void fieldsThatDoNotReadTheSameStayTexts(String field) {
        assertEquals(field, Parser.toIntIfInt(field));
    }

    @Test
    void anyLineEndingSeparatesTheLines() {
        Map<String, Object> session = Parser.parse("v=0\rs=-\nt=0 0\r\nm=audio 9 RTP/AVP 0\r\na=sendonly\n");

        assertEquals(0, session.get("version"));
        assertEquals("-", session.get("name"));
        assertEquals(map("start", 0, "stop", 0), session.get("timing"));
        assertEquals("sendonly", at(session, "media", 0, "direction"));
    }

    @Test
    void linesThatAreNotUnderstoodAreIgnored() {
        Map<String, Object> session = Parser.parse("v=0\r\nX=upper\r\n a=indented\r\nk=clear:key\r\n=\r\nvv\r\n");

        assertEquals(Map.of("version", 0, "media", List.of()), session);
    }

    @Test
    void unknownSessionAttributesAreKeptAsInvalid() {
        Map<String, Object> session = Parser.parse("v=0\r\na=foo:bar\r\na=msid-semantic:WMS\r\n");

        assertEquals(List.of(map("value", "foo:bar"), map("value", "msid-semantic:WMS")), session.get("invalid"));
    }

    @Test
    void mediaSectionsStartWithEmptyRtpAndFmtp() {
        Map<String, Object> session = Parser.parse("v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");

        assertEquals(
                map(
                        "rtp",
                        List.of(),
                        "fmtp",
                        List.of(),
                        "type",
                        "application",
                        "port",
                        9,
                        "protocol",
                        "UDP/DTLS/SCTP",
                        "payloads",
                        "webrtc-datachannel"),
                at(session, "media", 0));
    }

    @Test
    void theParsedSdpIsMutable() {
        Map<String, Object> session = Parser.parse("v=0\r\nm=audio 9 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n");

        session.put("name", "-");
        @SuppressWarnings("unchecked")
        var rtp = (List<Map<String, Object>>) at(session, "media", 0, "rtp");
        rtp.getFirst().put("rate", 16000);
        rtp.add(Map.of());

        assertEquals(2, rtp.size());
    }

    @Test
    void paramsWithoutValueMapToNull() {
        assertEquals(
                map("minptime", 10, "stereo", null, "useinbandfec", 1),
                Parser.parseParams("minptime=10;stereo; useinbandfec=1"));
        // A key of one character without a value is dropped, like in the original.
        assertEquals(map("ab", null), Parser.parseParams("a;ab;"));
        // Only the first = separates the key from the value.
        assertEquals(map("key", "a=b", "empty=", null), Parser.parseParams("key=a=b;empty="));
        assertEquals(
                map("profile-level-id", "42e01f", "level", 31), Parser.parseParams("profile-level-id=42e01f;level=31"));
    }

    @Test
    void payloadsAreConvertedLikeJavaScriptNumbers() {
        assertEquals(List.of(111, 103, 0), Parser.parsePayloads("111 103 0"));
        assertEquals(List.of(107), Parser.parsePayloads(107));
        assertEquals(List.of(1, 0, 2), Parser.parsePayloads("1  2"));

        List<Number> others = Parser.parsePayloads("webrtc-datachannel 0x10 1e2 1.5 * Infinity");
        assertTrue(Double.isNaN(others.get(0).doubleValue()));
        assertEquals(16, others.get(1));
        assertEquals(100, others.get(2));
        assertEquals(1.5, others.get(3));
        assertTrue(Double.isNaN(others.get(4).doubleValue()));
        assertEquals(Double.POSITIVE_INFINITY, others.get(5));
    }

    @Test
    void remoteCandidatesComeInGroupsOfThree() {
        assertEquals(
                List.of(
                        map("component", 1, "ip", "203.0.113.1", "port", 54400),
                        map("component", 2, "ip", "203.0.113.1", "port", 54401)),
                Parser.parseRemoteCandidates("1 203.0.113.1 54400 2 203.0.113.1 54401"));
        assertEquals(
                List.of(map("component", 1, "ip", "203.0.113.1", "port", null)),
                Parser.parseRemoteCandidates("1 203.0.113.1"));
    }

    @Test
    void imageAttributesWithoutBracketsAreReadLikeTheOriginal() {
        assertEquals(List.of(map(), map()), Parser.parseImageAttributes(" "));
        assertEquals(List.of(map("x", 1)), Parser.parseImageAttributes("[x=1]"));
    }

    @Test
    void simulcastFormatsCanBePaused() {
        List<List<Map<String, Object>>> streams = Parser.parseSimulcastStreamList("h,~m;;~");

        assertEquals(
                List.of(
                        List.of(map("scid", "h", "paused", false), map("scid", "m", "paused", true)),
                        List.of(map("scid", "", "paused", false)),
                        List.of(map("scid", "", "paused", true))),
                streams);
    }

    @Test
    void rulesAreTriedInOrder() {
        Object media = at(
                Parser.parse("m=video 9 RTP/AVP 96\r\na=rtcp-fb:96 trr-int 100\r\na=rtcp-fb:96 nack pli\r\n"),
                "media",
                0);

        assertEquals(List.of(map("payload", 96, "value", 100)), at(media, "rtcpFbTrrInt"));
        assertEquals(List.of(map("payload", 96, "type", "nack", "subtype", "pli")), at(media, "rtcpFb"));
        assertNull(at(media, "invalid"));
    }

    @Test
    void unknownLineTypesHaveNoRules() {
        assertTrue(Grammar.rules('k').isEmpty());
        assertFalse(Grammar.rules('a').isEmpty());
    }
}
