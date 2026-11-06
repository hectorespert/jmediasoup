/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code test/coverage.test.js} of sdp-transform: every attribute is understood. */
class CoverageTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "normal.sdp",
                "hacky.sdp",
                "icelite.sdp",
                "jssip.sdp",
                "jsep.sdp",
                // "alac.sdp", // deliberate invalids
                "onvif.sdp",
                "ssrc.sdp",
                "simulcast.sdp",
                "st2022-6.sdp",
                // "st2110-20.sdp", // deliberate invalids
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
                "rtcp-fb.sdp"
            })
    void noInvalids(String file) {
        for (Object media : (List<?>) ParserTest.parse(file).get("media")) {
            Map<?, ?> m = (Map<?, ?>) media;
            assertNull(m.get("invalid"), "no invalids in " + file + " at m=" + m.get("type"));
        }
    }
}
