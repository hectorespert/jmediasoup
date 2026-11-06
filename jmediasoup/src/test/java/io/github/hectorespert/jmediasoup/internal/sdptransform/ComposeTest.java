/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdptransform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code test/compose.test.js} of sdp-transform. */
class ComposeTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "normal.sdp",
                "hacky.sdp",
                "icelite.sdp",
                "invalid.sdp",
                "jssip.sdp",
                "jsep.sdp",
                "alac.sdp",
                "onvif.sdp",
                "ssrc.sdp",
                "simulcast.sdp",
                "st2022-6.sdp",
                "st2110-20.sdp",
                "sctp-dtls-26.sdp",
                "extmap-encrypt.sdp",
                "dante-aes67.sdp",
                "bfcp.sdp",
                "tcp-active.sdp",
                "tcp-passive.sdp",
                "mediaclk-avbtp.sdp",
                "mediaclk-ptp-v2-w-rate.sdp",
                "mediaclk-ptp-v2.sdp",
                "mediaclk-rtp.sdp",
                "ts-refclk-media.sdp",
                "ts-refclk-sess.sdp",
                "rtcp-fb.sdp",
                "webrtc-java-0.20.0/offer-datachannel.sdp",
                "webrtc-java-0.20.0/offer-native-capabilities.sdp",
                "webrtc-java-0.20.0/offer-recvonly-no-tracks.sdp",
                "webrtc-java-0.20.0/offer-sendonly.sdp",
                "webrtc-java-0.20.0/offer-simulcast.sdp"
            })
    void verifyCompose(String file) {
        Map<String, Object> obj = Parser.parse(ParserTest.read(file));
        String sdp2 = Writer.write(obj);
        Map<String, Object> obj2 = Parser.parse(sdp2);

        assertEquals(obj, obj2, "parse ∘ write ∘ parse === parse | " + file);
        // This only tests that (parse ∘ write) == Id on the image of the parse.

        // It also doesn't test if (write ∘ parse) is the identity: which it isnt.
        // Properties may get reordered slightly (up to RFC legality).

        // However: (write ∘ parse) should be the identity on the image of write
        // because our own ordering is deterministic.
        assertEquals(sdp2, Writer.write(obj2), "write ∘ parse === Id on Im(write) for " + file);
    }
}
