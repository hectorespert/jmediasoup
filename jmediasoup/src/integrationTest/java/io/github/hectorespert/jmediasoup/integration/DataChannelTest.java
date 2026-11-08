/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.DataChannelMessage;
import io.github.hectorespert.jmediasoup.DataConsumer;
import io.github.hectorespert.jmediasoup.DataProducer;
import io.github.hectorespert.jmediasoup.DataProducerOptions;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.Subscription;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewDataConsumer;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Data channels through mediasoup: a jmediasoup peer produces data on the {@code chat} channel of
 * the demo, and the server consumes it for another one, in the same process.
 */
@ExtendWith(MediasoupServer.class)
class DataChannelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private DemoPeer alice;
    private DemoPeer bob;
    private SendTransport sendTransport;
    private RecvTransport recvTransport;

    @BeforeEach
    void connect(MediasoupServer.Server server) throws Exception {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        String room = "data-" + UUID.randomUUID();
        alice = DemoPeer.connect(server, room, "alice");
        bob = DemoPeer.connect(server, room, "bob");

        // bob joins with SCTP and a receive transport with SCTP, so that the server consumes data for
        // him.
        var bobDevice = new Device(bobFactory);
        await(bobDevice.load(await(bob.routerRtpCapabilities())));
        TransportOptions recvOptions =
                await(bob.createWebRtcTransport(Direction.CONSUMER, bobDevice.sctpCapabilities()));
        await(bob.join("bob", bobDevice.recvRtpCapabilities(), bobDevice.sctpCapabilities()));
        recvTransport =
                bobDevice.createRecvTransport(recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));

        var aliceDevice = new Device(aliceFactory);
        await(aliceDevice.load(await(alice.routerRtpCapabilities())));
        await(alice.join("alice", aliceDevice.recvRtpCapabilities()));
        TransportOptions sendOptions =
                await(alice.createWebRtcTransport(Direction.PRODUCER, aliceDevice.sctpCapabilities()));
        sendTransport = aliceDevice.createSendTransport(
                sendOptions,
                dtls -> alice.connectWebRtcTransport(sendOptions.id(), dtls),
                request -> CompletableFuture.failedFuture(new UnsupportedOperationException("no media")),
                request -> alice.produceData(sendOptions.id(), request, "chat"));
    }

    @AfterEach
    void close() {
        sendTransport.close();
        recvTransport.close();
        alice.close();
        bob.close();
        aliceFactory.close();
        bobFactory.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    /** A data channel that alice sends on and bob receives from, both open. */
    private record Channel(
            DataProducer producer, DataConsumer consumer, LinkedBlockingQueue<DataChannelMessage> received) {

        DataChannelMessage next() throws InterruptedException {
            DataChannelMessage message = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(message != null, "no message received");
            return message;
        }
    }

    private Channel open(DataProducerOptions options) throws Exception {
        DataProducer producer = await(sendTransport.produceData(options));
        awaitOpen(producer::onOpen);

        // The bot of the demo also has a data producer, which bob consumes when he joins.
        NewDataConsumer newDataConsumer;
        do {
            newDataConsumer = bob.nextDataConsumer(TIMEOUT);
            newDataConsumer.accept();
        } while (!"chat".equals(newDataConsumer.channel()));
        assertEquals("alice", newDataConsumer.peerId());
        assertEquals(producer.id(), newDataConsumer.options().dataProducerId());

        DataConsumer consumer = await(recvTransport.consumeData(newDataConsumer.options()));
        var received = new LinkedBlockingQueue<DataChannelMessage>();
        consumer.onMessage(received::add);
        awaitOpen(consumer::onOpen);
        return new Channel(producer, consumer, received);
    }

    // onOpen also runs if the channel is already open.
    private static void awaitOpen(Function<Runnable, Subscription> onOpen) throws InterruptedException {
        var opened = new CountDownLatch(1);
        onOpen.apply(opened::countDown);
        assertTrue(opened.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "data channel not open");
    }

    @Test
    void textMessagesArriveThroughTheServer() throws Exception {
        Channel channel = open(DataProducerOptions.builder().label("chat").build());

        channel.producer().send("hello");
        channel.producer().send("bye");

        assertEquals("chat", channel.consumer().label());
        assertEquals(DataChannelMessage.text("hello"), channel.next());
        assertEquals(DataChannelMessage.text("bye"), channel.next());
    }

    @Test
    void binaryMessagesArriveThroughTheServer() throws Exception {
        Channel channel = open(DataProducerOptions.builder().label("chat").build());
        byte[] bytes = new byte[1000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }

        channel.producer().send(bytes);

        DataChannelMessage message = channel.next();
        assertTrue(message.isBinary());
        assertEquals(DataChannelMessage.binary(bytes), message);
    }

    @Test
    void anUnorderedChannelWithMaxRetransmitsReachesTheServer() throws Exception {
        Channel channel = open(
                DataProducerOptions.builder().label("chat").maxRetransmits(1).build());

        // mediasoup gives the data consumer the reliability of the data producer.
        assertEquals(false, channel.consumer().sctpStreamParameters().ordered());
        assertEquals(1, channel.consumer().sctpStreamParameters().maxRetransmits());

        var sent = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            sent.add("message " + i);
            channel.producer().send(sent.getLast());
        }
        var received = new ArrayList<String>();
        for (int i = 0; i < sent.size(); i++) {
            received.add(channel.next().asText());
        }
        // Without loss on the loopback every message arrives, maybe out of order.
        assertEquals(sent.stream().sorted().toList(), received.stream().sorted().toList());
    }

    @Test
    void closingTheReceiveTransportClosesTheDataConsumer() throws Exception {
        Channel channel = open(DataProducerOptions.builder().label("chat").build());
        var events = new LinkedBlockingQueue<String>();
        channel.consumer().onTransportClose(() -> events.add("transportclose"));

        recvTransport.close();

        assertEquals("transportclose", events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertTrue(channel.consumer().closed());
        assertEquals(List.of(), List.copyOf(events));
    }
}
