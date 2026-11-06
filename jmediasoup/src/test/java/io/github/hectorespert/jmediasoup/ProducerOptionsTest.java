/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.fake.FakeMediaStreamTrack;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProducerOptionsTest {

    @Test
    void builderStartsWithTheDefaultsOfTheOriginal() {
        var track = FakeMediaStreamTrack.audio();

        ProducerOptions options = ProducerOptions.builder(track).build();

        assertSame(track, options.track());
        assertTrue(options.stopTracks());
        assertTrue(options.disableTrackOnPause());
        assertFalse(options.zeroRtpOnPause());
        assertEquals(List.of(), options.encodings());
        assertNull(options.codecOptions());
        assertNull(options.codec());
        assertNull(options.streamId());
        assertEquals(Map.of(), options.appData());
    }

    @Test
    void toBuilderCopiesEveryField() {
        var encodings = new ArrayList<>(List.of(
                RtpEncodingParameters.builder().rid("r0").maxBitrate(100_000).build(),
                RtpEncodingParameters.builder().rid("r1").maxBitrate(300_000).build()));
        ProducerOptions options = ProducerOptions.builder(FakeMediaStreamTrack.video())
                .streamId("camera")
                .encodings(encodings)
                .codecOptions(ProducerCodecOptions.builder()
                        .videoGoogleStartBitrate(1000)
                        .build())
                .stopTracks(false)
                .disableTrackOnPause(false)
                .zeroRtpOnPause(true)
                .appData(Map.of("source", "webcam"))
                .build();
        encodings.clear();

        assertEquals(2, options.encodings().size());
        assertEquals(options, options.toBuilder().build());
        assertTrue(options.zeroRtpOnPause());
    }

    @Test
    void trackIsRequired() {
        assertEquals(
                "track",
                assertThrows(
                                NullPointerException.class,
                                () -> ProducerOptions.builder(null).build())
                        .getMessage());
    }

    @Test
    void codecOptionsCheckTheirRanges() {
        var options = ProducerCodecOptions.builder()
                .opusStereo(true)
                .opusNack(true)
                .opusPtime(20)
                .build();

        assertEquals(options, options.toBuilder().build());
        assertNull(options.opusFec());
        assertThrows(
                IllegalArgumentException.class,
                () -> ProducerCodecOptions.builder().opusPtime(0).build());
    }
}
