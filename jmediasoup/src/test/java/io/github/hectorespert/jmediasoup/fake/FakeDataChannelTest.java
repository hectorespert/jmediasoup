/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.handler.DataChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeDataChannelTest {

    private final FakeDataChannel channel = new FakeDataChannel("chat", "");
    private final List<String> events = new ArrayList<>();

    FakeDataChannelTest() {
        channel.setObserver(new DataChannel.Observer() {
            @Override
            public void onOpen() {
                events.add("open");
            }

            @Override
            public void onClose() {
                events.add("close");
            }

            @Override
            public void onError(Throwable error) {
                events.add("error " + error.getMessage());
            }

            @Override
            public void onMessage(DataChannelMessage message) {
                events.add("message " + message.asText());
            }

            @Override
            public void onBufferedAmountLow() {
                events.add("low");
            }
        });
    }

    @Test
    void sendsOnlyWhenOpen() throws Exception {
        assertThrows(InvalidStateException.class, () -> channel.send(DataChannelMessage.text("early")));

        channel.open();
        channel.send(DataChannelMessage.text("hello"));

        assertEquals(RTCDataChannelState.OPEN, channel.readyState());
        assertEquals(List.of(DataChannelMessage.text("hello")), channel.sentMessages());
    }

    @Test
    void deliversTheEventsToTheObserver() {
        channel.open();
        channel.receive(DataChannelMessage.text("hi"));
        channel.fail(new IllegalStateException("sctp failure"));
        channel.close();
        channel.close();

        assertEquals(List.of("open", "message hi", "error sctp failure", "close"), events);
        assertEquals(RTCDataChannelState.CLOSED, channel.readyState());
    }

    @Test
    void notifiesWhenTheBufferedAmountFallsToTheThreshold() {
        channel.setBufferedAmountLowThreshold(1000);
        channel.setBufferedAmount(5000);
        channel.setBufferedAmount(2000);
        channel.setBufferedAmount(1000);
        channel.setBufferedAmount(500);

        assertEquals(List.of("low"), events);
    }
}
