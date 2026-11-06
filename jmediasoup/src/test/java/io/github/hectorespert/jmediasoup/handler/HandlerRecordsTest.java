/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import io.github.hectorespert.jmediasoup.fake.FakeDataChannel;
import io.github.hectorespert.jmediasoup.fake.FakeMediaStreamTrack;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class HandlerRecordsTest {

    private static final RtpParameters RTP_PARAMETERS =
            FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters();

    @Test
    void absentListsAreEmpty() {
        TransportOptions transport = FakeParameters.transportRemoteParameters();

        var options = new HandlerOptions(
                TransportDirection.SEND,
                transport.iceParameters(),
                null,
                transport.dtlsParameters(),
                null,
                null,
                null,
                capabilities -> new ExtendedRtpCapabilities(null, null),
                new NoListener());
        var sendOptions = new HandlerSendOptions(FakeMediaStreamTrack.audio(), null, null, null, null);

        assertEquals(List.of(), options.iceCandidates());
        assertEquals(List.of(), options.iceServers());
        assertEquals(List.of(), sendOptions.encodings());
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThrows(NullPointerException.class, () -> new HandlerSendOptions(null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new HandlerSendResult("", RTP_PARAMETERS, null));
        assertThrows(NullPointerException.class, () -> new HandlerSendResult("1", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HandlerReceiveOptions("", MediaKind.AUDIO, RTP_PARAMETERS, null));
        assertThrows(NullPointerException.class, () -> new HandlerReceiveOptions("t", null, RTP_PARAMETERS, null));
        assertThrows(NullPointerException.class, () -> new HandlerReceiveResult("1", null, null));
        assertThrows(NullPointerException.class, () -> new HandlerSendDataChannelOptions(null));
        assertThrows(
                NullPointerException.class,
                () -> new HandlerSendDataChannelResult(
                        null, new SctpStreamParameters(0, null, null, null, null, null)));
        assertThrows(NullPointerException.class, () -> new HandlerReceiveDataChannelOptions(null, null, null, null));
        assertThrows(NullPointerException.class, () -> new HandlerReceiveDataChannelResult(null));
        new HandlerReceiveDataChannelResult(new FakeDataChannel("chat", ""));
    }

    private static final class NoListener implements HandlerListener {

        @Override
        public CompletionStage<Void> connect(DtlsParameters dtlsParameters) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void connectionStateChange(ConnectionState state) {}

        @Override
        public void iceGatheringStateChange(IceGatheringState state) {}

        @Override
        public void iceCandidateError(RTCPeerConnectionIceErrorEvent event) {}
    }
}
