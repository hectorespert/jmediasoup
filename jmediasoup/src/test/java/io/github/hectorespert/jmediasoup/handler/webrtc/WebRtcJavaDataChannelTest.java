/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.handler.DataChannel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link WebRtcJavaDataChannel} with the webrtc-java natives, over a negotiated channel between
 * two peer connections of the same process.
 */
class WebRtcJavaDataChannelTest {

    private static PeerConnectionFactory peerConnectionFactory;

    private RTCPeerConnection left;
    private RTCPeerConnection right;
    private WebRtcJavaDataChannel leftChannel;
    private WebRtcJavaDataChannel rightChannel;
    private final Events leftEvents = new Events();
    private final Events rightEvents = new Events();

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
    }

    @AfterAll
    static void disposePeerConnectionFactory() {
        peerConnectionFactory.dispose();
    }

    // Records the events of a channel, as the data producers and consumers would receive them.
    private static final class Events implements DataChannel.Observer {
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final LinkedBlockingQueue<DataChannelMessage> messages = new LinkedBlockingQueue<>();
        final List<String> bufferedAmountLow = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen() {
            open.countDown();
        }

        @Override
        public void onClose() {
            closed.countDown();
        }

        @Override
        public void onMessage(DataChannelMessage message) {
            messages.add(message);
        }

        @Override
        public void onBufferedAmountLow() {
            bufferedAmountLow.add("bufferedamountlow");
        }
    }

    @BeforeEach
    void connect() throws Exception {
        var leftCandidates = new LinkedBlockingQueue<RTCIceCandidate>();
        var rightCandidates = new LinkedBlockingQueue<RTCIceCandidate>();
        left = peerConnectionFactory.createPeerConnection(new RTCConfiguration(), observer(leftCandidates));
        right = peerConnectionFactory.createPeerConnection(new RTCConfiguration(), observer(rightCandidates));

        var init = new RTCDataChannelInit();
        init.negotiated = true;
        init.id = 3;
        init.protocol = "chat";
        leftChannel = new WebRtcJavaDataChannel(left.createDataChannel("label", init));
        rightChannel = new WebRtcJavaDataChannel(right.createDataChannel("label", init));
        leftChannel.setObserver(leftEvents);
        rightChannel.setObserver(rightEvents);

        var offer = await(PeerConnections.createOffer(left, new RTCOfferOptions()));
        await(PeerConnections.setLocalDescription(left, offer));
        await(PeerConnections.setRemoteDescription(right, offer));
        var answer = await(PeerConnections.createAnswer(right));
        await(PeerConnections.setLocalDescription(right, answer));
        await(PeerConnections.setRemoteDescription(left, answer));

        // Exchange the candidates gathered meanwhile and later, until both channels open.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((leftEvents.open.getCount() > 0 || rightEvents.open.getCount() > 0) && System.nanoTime() < deadline) {
            RTCIceCandidate candidate;
            while ((candidate = leftCandidates.poll()) != null) {
                right.addIceCandidate(candidate);
            }
            while ((candidate = rightCandidates.poll()) != null) {
                left.addIceCandidate(candidate);
            }
            Thread.sleep(20);
        }
        assertTrue(leftEvents.open.await(1, TimeUnit.SECONDS), "left channel open");
        assertTrue(rightEvents.open.await(1, TimeUnit.SECONDS), "right channel open");
    }

    @AfterEach
    void close() {
        leftChannel.dispose();
        rightChannel.dispose();
        left.close();
        right.close();
    }

    private static PeerConnectionObserver observer(LinkedBlockingQueue<RTCIceCandidate> candidates) {
        return candidates::add;
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    void itHasTheLabelProtocolAndStateOfTheChannel() {
        assertEquals("label", leftChannel.label());
        assertEquals("chat", leftChannel.protocol());
        assertEquals(RTCDataChannelState.OPEN, leftChannel.readyState());
    }

    @Test
    void textAndBinaryMessagesArrive() throws Exception {
        leftChannel.send(DataChannelMessage.text("hello"));
        leftChannel.send(DataChannelMessage.binary(new byte[] {1, 2, 3}));

        assertEquals(DataChannelMessage.text("hello"), rightEvents.messages.poll(5, TimeUnit.SECONDS));
        assertEquals(DataChannelMessage.binary(new byte[] {1, 2, 3}), rightEvents.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void bufferedAmountLowFiresWhenTheBufferDrains() throws Exception {
        leftChannel.setBufferedAmountLowThreshold(0);

        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < 32; i++) {
            leftChannel.send(DataChannelMessage.binary(chunk));
        }

        // It may also fire each time the buffer drains between two sends.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((leftChannel.bufferedAmount() > 0 || leftEvents.bufferedAmountLow.isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, leftChannel.bufferedAmount());
        assertFalse(leftEvents.bufferedAmountLow.isEmpty());
    }

    @Test
    void closingOneSideClosesBoth() throws Exception {
        leftChannel.close();

        assertTrue(leftEvents.closed.await(5, TimeUnit.SECONDS), "left channel closed");
        assertTrue(rightEvents.closed.await(5, TimeUnit.SECONDS), "right channel closed");
        assertEquals(RTCDataChannelState.CLOSED, rightChannel.readyState());
        assertThrows(InvalidStateException.class, () -> leftChannel.send(DataChannelMessage.text("late")));
    }

    @Test
    void onceDisposedItKeepsItsLastValues() throws MediasoupClientException {
        leftChannel.dispose();
        leftChannel.dispose();
        leftChannel.close();

        assertEquals("label", leftChannel.label());
        assertEquals(RTCDataChannelState.CLOSED, leftChannel.readyState());
        assertEquals(0, leftChannel.bufferedAmount());
        assertThrows(InvalidStateException.class, () -> leftChannel.send(DataChannelMessage.text("late")));
    }
}
