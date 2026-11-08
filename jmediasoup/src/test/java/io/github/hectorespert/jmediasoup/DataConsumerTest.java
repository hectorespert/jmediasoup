/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.RTCDataChannelState;
import io.github.hectorespert.jmediasoup.fake.FakeDataChannel;
import io.github.hectorespert.jmediasoup.fake.FakeHandler;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ports the consumeData and data consumer tests of mediasoup-client ({@code test.ts}) and pymediasoup. */
class DataConsumerTest {

    private final FakeHandler.Factory factory = FakeHandler.createFactory();
    private final Device device = new Device(factory);
    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private final RecvTransport transport;

    DataConsumerTest() throws InvalidStateException {
        device.load(FakeParameters.routerRtpCapabilities()).join();
        transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), dtls -> {
            connects.add(dtls);
            return CompletableFuture.completedFuture(null);
        });
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static Throwable cause(CompletableFuture<?> future) {
        return assertThrows(CompletionException.class, () -> await(future)).getCause();
    }

    // Waits until the executor of the transport has run what was submitted before.
    private static void drain(Transport transport) {
        await(CompletableFuture.runAsync(() -> {}, transport.executor()));
    }

    private FakeHandler handler() {
        return assertInstanceOf(FakeHandler.class, transport.handler());
    }

    private DataConsumer consumeData() {
        return await(transport.consumeData(FakeParameters.dataConsumerRemoteParameters()));
    }

    @Test
    void consumeDataSucceeds() {
        DataConsumerOptions remote = FakeParameters.dataConsumerRemoteParameters();

        DataConsumer dataConsumer = await(transport.consumeData(new DataConsumerOptions(
                remote.id(),
                remote.dataProducerId(),
                remote.sctpStreamParameters(),
                "FOO",
                "BAR",
                Map.of("bar", "BAR"))));

        // The first data channel connects the transport, as DTLS client.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.CLIENT, connects.getFirst().role());

        assertEquals(remote.id(), dataConsumer.id());
        assertEquals(remote.dataProducerId(), dataConsumer.dataProducerId());
        assertFalse(dataConsumer.closed());
        assertEquals(666, dataConsumer.sctpStreamParameters().streamId());
        // The fixture has a maxPacketLifeTime and no ordered, so it is unordered.
        assertEquals(false, dataConsumer.sctpStreamParameters().ordered());
        assertEquals(5000, dataConsumer.sctpStreamParameters().maxPacketLifeTime());
        assertEquals("FOO", dataConsumer.label());
        assertEquals("BAR", dataConsumer.protocol());
        assertEquals(RTCDataChannelState.CONNECTING, dataConsumer.readyState());
        assertEquals(Map.of("bar", "BAR"), dataConsumer.appData());
    }

    @Test
    void theObserverEmitsNewDataConsumer() {
        var created = new CopyOnWriteArrayList<DataConsumer>();
        transport.observer().onNewDataConsumer(created::add);

        DataConsumer dataConsumer = consumeData();
        drain(transport);

        assertEquals(List.of(dataConsumer), created);
    }

    @Test
    void consumeDataWithoutStreamIdRejectsWithIllegalArgument() {
        DataConsumerOptions remote = FakeParameters.dataConsumerRemoteParameters();

        Throwable error = cause(transport.consumeData(new DataConsumerOptions(
                remote.id(),
                remote.dataProducerId(),
                new SctpStreamParameters(null, true, null, null, null, null),
                null,
                null,
                Map.of())));

        assertInstanceOf(IllegalArgumentException.class, error);
    }

    @Test
    void consumeDataWithoutSctpRejectsWithUnsupported() throws InvalidStateException {
        RecvTransport withoutSctp = device.createRecvTransport(
                FakeParameters.transportRemoteParameters().toBuilder()
                        .sctpParameters(null)
                        .build(),
                dtls -> CompletableFuture.completedFuture(null));

        assertInstanceOf(
                UnsupportedException.class,
                cause(withoutSctp.consumeData(FakeParameters.dataConsumerRemoteParameters())));
    }

    @Test
    void consumeDataInAClosedTransportRejectsWithInvalidState() {
        transport.close();

        assertInstanceOf(
                InvalidStateException.class,
                cause(transport.consumeData(FakeParameters.dataConsumerRemoteParameters())));
    }

    @Test
    void theMessagesReachTheListeners() {
        DataConsumer dataConsumer = consumeData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        var messages = new CopyOnWriteArrayList<DataChannelMessage>();
        dataConsumer.onOpen(() -> events.add("open"));
        dataConsumer.onError(error -> events.add("error " + error.getMessage()));
        dataConsumer.onMessage(messages::add);

        dataChannel.open();
        dataChannel.receive(DataChannelMessage.text("hello"));
        dataChannel.receive(DataChannelMessage.binary(new byte[] {1, 2, 3}));
        dataChannel.fail(new IllegalStateException("boom"));
        drain(transport);

        assertEquals(List.of("open", "error boom"), events);
        assertEquals(
                List.of(DataChannelMessage.text("hello"), DataChannelMessage.binary(new byte[] {1, 2, 3})), messages);
    }

    @Test
    void closeClosesTheDataChannelAndEmitsTheObserverClose() {
        DataConsumer dataConsumer = consumeData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        dataConsumer.onClose(() -> events.add("close"));
        dataConsumer.onMessage(message -> events.add("message"));
        dataConsumer.observer().onClose(() -> events.add("observer close"));

        dataConsumer.close();
        dataChannel.receive(DataChannelMessage.text("late"));
        drain(transport);

        assertTrue(dataConsumer.closed());
        assertEquals(RTCDataChannelState.CLOSED, dataChannel.readyState());
        assertEquals(List.of("observer close"), events);
    }

    @Test
    void theDataChannelClosingClosesTheDataConsumer() {
        DataConsumer dataConsumer = consumeData();
        var events = new CopyOnWriteArrayList<String>();
        dataConsumer.onClose(() -> events.add("close"));
        dataConsumer.observer().onClose(() -> events.add("observer close"));

        handler().dataChannels().getFirst().close();
        drain(transport);

        assertTrue(dataConsumer.closed());
        assertEquals(List.of("close", "observer close"), events);
    }

    @Test
    void closingTheTransportClosesTheDataConsumers() {
        DataConsumer dataConsumer = consumeData();
        var events = new CopyOnWriteArrayList<String>();
        dataConsumer.onClose(() -> events.add("close"));
        dataConsumer.onTransportClose(() -> events.add("transportclose"));
        dataConsumer.observer().onClose(() -> events.add("observer close"));

        transport.close();
        drain(transport);

        assertTrue(dataConsumer.closed());
        assertEquals(
                RTCDataChannelState.CLOSED, handler().dataChannels().getFirst().readyState());
        assertEquals(List.of("transportclose", "observer close"), events);
    }

    @Test
    void anOpenListenerAddedOnceOpenRunsOnce() {
        DataConsumer dataConsumer = consumeData();
        handler().dataChannels().getFirst().open();
        drain(transport);
        var events = new CopyOnWriteArrayList<String>();

        dataConsumer.onOpen(() -> events.add("open"));
        drain(transport);

        assertEquals(List.of("open"), events);
    }

    @Test
    void aClosedDataConsumerDoesNotRunALateOpenListener() {
        DataConsumer dataConsumer = consumeData();
        handler().dataChannels().getFirst().open();
        dataConsumer.close();
        var events = new CopyOnWriteArrayList<String>();

        dataConsumer.onOpen(() -> events.add("open"));
        drain(transport);

        assertEquals(List.of(), events);
    }
}
