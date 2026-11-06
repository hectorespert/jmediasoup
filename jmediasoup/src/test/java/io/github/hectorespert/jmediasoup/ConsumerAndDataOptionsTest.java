/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsumerAndDataOptionsTest {

    private static final RtpParameters RTP_PARAMETERS = new RtpParameters(
            null, List.of(new RtpCodecParameters("audio/opus", 100, 48000, 2, null, null)), null, null, null, null);

    @Test
    void consumerOptionsNeedTheServerFields() {
        var options = new ConsumerOptions("consumer-1", "producer-1", MediaKind.AUDIO, RTP_PARAMETERS, null, null);

        assertEquals(Map.of(), options.appData());
        assertNull(options.streamId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerOptions("consumer-1", "", MediaKind.AUDIO, RTP_PARAMETERS, null, null));
        assertEquals(
                "rtpParameters",
                assertThrows(
                                NullPointerException.class,
                                () -> new ConsumerOptions(
                                        "consumer-1", "producer-1", MediaKind.AUDIO, null, null, null))
                        .getMessage());
    }

    @Test
    void dataProducerOptionsRejectContradictoryReliability() {
        var options = DataProducerOptions.builder()
                .ordered(false)
                .maxRetransmits(3)
                .label("chat")
                .build();

        assertEquals(options, options.toBuilder().build());
        assertNull(DataProducerOptions.builder().build().ordered());
        assertEquals(
                "cannot be ordered with maxPacketLifeTime or maxRetransmits",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> DataProducerOptions.builder()
                                        .ordered(true)
                                        .maxPacketLifeTime(500)
                                        .build())
                        .getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> DataProducerOptions.builder()
                        .maxPacketLifeTime(500)
                        .maxRetransmits(3)
                        .build());
    }

    @Test
    void dataConsumerOptionsNeedTheServerFields() {
        var stream = new SctpStreamParameters(1, true, null, null, null, null);
        var options = new DataConsumerOptions("data-consumer-1", "data-producer-1", stream, "chat", null, null);

        assertEquals("chat", options.label());
        assertThrows(
                NullPointerException.class,
                () -> new DataConsumerOptions("data-consumer-1", "data-producer-1", null, null, null, null));
    }
}
