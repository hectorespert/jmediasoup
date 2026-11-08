/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ports the produceData and data producer tests of mediasoup-client ({@code test.ts}) and pymediasoup. */
class DataProducerTest {

    private final FakeHandler.Factory factory = FakeHandler.createFactory();
    private final Device device = new Device(factory);
    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private final List<ProduceDataRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final SendTransport transport;

    DataProducerTest() throws InvalidStateException {
        device.load(FakeParameters.routerRtpCapabilities()).join();
        transport = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> {
                    connects.add(dtls);
                    return CompletableFuture.completedFuture(null);
                },
                request -> CompletableFuture.completedFuture("producer"),
                request -> {
                    requests.add(request);
                    return CompletableFuture.completedFuture("data-producer-" + nextId.getAndIncrement());
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

    private DataProducer produceData() {
        return await(transport.produceData(DataProducerOptions.builder().build()));
    }

    @Test
    void produceDataSucceeds() {
        DataProducer dataProducer = await(transport.produceData(DataProducerOptions.builder()
                .ordered(false)
                .maxPacketLifeTime(5555)
                .label("FOO")
                .protocol("BAR")
                .appData(Map.of("foo", "FOO"))
                .build()));

        // The first data channel connects the transport, as DTLS server.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.SERVER, connects.getFirst().role());

        ProduceDataRequest request = requests.getFirst();
        assertNotNull(request.sctpStreamParameters().streamId());
        assertEquals(false, request.sctpStreamParameters().ordered());
        assertEquals(5555, request.sctpStreamParameters().maxPacketLifeTime());
        assertNull(request.sctpStreamParameters().maxRetransmits());
        assertEquals("FOO", request.label());
        assertEquals("BAR", request.protocol());
        assertEquals(Map.of("foo", "FOO"), request.appData());

        assertEquals("data-producer-1", dataProducer.id());
        assertFalse(dataProducer.closed());
        assertEquals(request.sctpStreamParameters(), dataProducer.sctpStreamParameters());
        assertEquals("FOO", dataProducer.label());
        assertEquals("BAR", dataProducer.protocol());
        assertEquals(RTCDataChannelState.CONNECTING, dataProducer.readyState());
        assertEquals(Map.of("foo", "FOO"), dataProducer.appData());
    }

    @Test
    void anAbsentOrderedIsTrueWithoutLimitsAndFalseWithThem() {
        await(transport.produceData(DataProducerOptions.builder().build()));
        await(transport.produceData(
                DataProducerOptions.builder().maxRetransmits(0).build()));

        assertEquals(true, requests.get(0).sctpStreamParameters().ordered());
        assertEquals("", requests.get(0).label());
        assertEquals("", requests.get(0).protocol());
        // ⚠️ The original ignores a limit of 0; here it makes the channel unordered.
        assertEquals(false, requests.get(1).sctpStreamParameters().ordered());
        assertEquals(0, requests.get(1).sctpStreamParameters().maxRetransmits());
    }

    @Test
    void eachDataChannelHasItsOwnStream() {
        DataProducer first = produceData();
        DataProducer second = produceData();

        assertFalse(first.sctpStreamParameters()
                .streamId()
                .equals(second.sctpStreamParameters().streamId()));
    }

    @Test
    void theObserverEmitsNewDataProducer() {
        var created = new CopyOnWriteArrayList<DataProducer>();
        transport.observer().onNewDataProducer(created::add);

        DataProducer dataProducer = produceData();
        drain(transport);

        assertEquals(List.of(dataProducer), created);
    }

    @Test
    void produceDataWithoutHandlerRejectsWithInvalidState() throws InvalidStateException {
        SendTransport withoutHandler = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture("producer"));

        assertInstanceOf(
                InvalidStateException.class,
                cause(withoutHandler.produceData(DataProducerOptions.builder().build())));
    }

    @Test
    void produceDataWithoutSctpRejectsWithUnsupported() throws InvalidStateException {
        SendTransport withoutSctp = device.createSendTransport(
                FakeParameters.transportRemoteParameters().toBuilder()
                        .sctpParameters(null)
                        .build(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture("producer"),
                request -> CompletableFuture.completedFuture("data-producer"));

        assertInstanceOf(
                UnsupportedException.class,
                cause(withoutSctp.produceData(DataProducerOptions.builder().build())));
    }

    @Test
    void produceDataInAClosedTransportRejectsWithInvalidState() {
        transport.close();

        assertInstanceOf(
                InvalidStateException.class,
                cause(transport.produceData(DataProducerOptions.builder().build())));
    }

    @Test
    void aFailingProduceDataHandlerClosesTheDataChannel() throws InvalidStateException {
        SendTransport failing = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture("producer"),
                request -> CompletableFuture.failedFuture(new IllegalStateException("server error")));

        Throwable error =
                cause(failing.produceData(DataProducerOptions.builder().build()));

        assertInstanceOf(MediasoupClientException.class, error);
        FakeDataChannel dataChannel = assertInstanceOf(FakeHandler.class, failing.handler())
                .dataChannels()
                .getFirst();
        assertEquals(RTCDataChannelState.CLOSED, dataChannel.readyState());
    }

    @Test
    void sendGoesThroughTheDataChannel() throws MediasoupClientException {
        DataProducer dataProducer = produceData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        dataChannel.open();

        dataProducer.send("hello");
        dataProducer.send(new byte[] {1, 2, 3});
        dataProducer.send(DataChannelMessage.text("bye"));

        assertEquals(
                List.of(
                        DataChannelMessage.text("hello"),
                        DataChannelMessage.binary(new byte[] {1, 2, 3}),
                        DataChannelMessage.text("bye")),
                dataChannel.sentMessages());
    }

    @Test
    void sendOnAClosedDataProducerThrowsInvalidState() {
        DataProducer dataProducer = produceData();
        handler().dataChannels().getFirst().open();
        dataProducer.close();

        assertThrows(InvalidStateException.class, () -> dataProducer.send("hello"));
    }

    @Test
    void theEventsOfTheDataChannelReachTheListeners() {
        DataProducer dataProducer = produceData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        dataProducer.onOpen(() -> events.add("open"));
        dataProducer.onError(error -> events.add("error " + error.getMessage()));
        dataProducer.onBufferedAmountLow(() -> events.add("bufferedamountlow"));

        dataChannel.open();
        dataChannel.fail(new IllegalStateException("boom"));
        dataProducer.setBufferedAmountLowThreshold(100);
        dataChannel.setBufferedAmount(500);
        dataChannel.setBufferedAmount(50);
        // A data producer discards what it receives.
        dataChannel.receive(DataChannelMessage.text("ignored"));
        drain(transport);

        assertEquals(List.of("open", "error boom", "bufferedamountlow"), events);
        assertEquals(100, dataProducer.bufferedAmountLowThreshold());
        assertEquals(50, dataProducer.bufferedAmount());
        assertEquals(RTCDataChannelState.OPEN, dataProducer.readyState());
    }

    @Test
    void closeClosesTheDataChannelAndEmitsTheObserverClose() {
        DataProducer dataProducer = produceData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        dataProducer.onClose(() -> events.add("close"));
        dataProducer.onTransportClose(() -> events.add("transportclose"));
        dataProducer.observer().onClose(() -> events.add("observer close"));

        dataProducer.close();
        dataProducer.close();
        drain(transport);

        assertTrue(dataProducer.closed());
        assertEquals(RTCDataChannelState.CLOSED, dataChannel.readyState());
        // The close of the channel that close() causes is not the close event.
        assertEquals(List.of("observer close"), events);
    }

    @Test
    void theDataChannelClosingClosesTheDataProducer() {
        DataProducer dataProducer = produceData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        dataProducer.onClose(() -> events.add("close"));
        dataProducer.observer().onClose(() -> events.add("observer close"));

        dataChannel.close();
        drain(transport);

        assertTrue(dataProducer.closed());
        assertEquals(List.of("close", "observer close"), events);
    }

    @Test
    void closingTheTransportClosesTheDataProducers() {
        DataProducer dataProducer = produceData();
        FakeDataChannel dataChannel = handler().dataChannels().getFirst();
        var events = new CopyOnWriteArrayList<String>();
        dataProducer.onClose(() -> events.add("close"));
        dataProducer.onTransportClose(() -> events.add("transportclose"));
        dataProducer.observer().onClose(() -> events.add("observer close"));

        transport.close();
        drain(transport);

        assertTrue(dataProducer.closed());
        assertEquals(RTCDataChannelState.CLOSED, dataChannel.readyState());
        assertEquals(List.of("transportclose", "observer close"), events);
    }

    @Test
    void theDataProducerKeepsTheStreamParametersOfTheHandler() {
        DataProducer dataProducer = await(transport.produceData(
                DataProducerOptions.builder().maxRetransmits(3).build()));

        SctpStreamParameters parameters = dataProducer.sctpStreamParameters();
        assertSame(requests.getFirst().sctpStreamParameters(), parameters);
        assertEquals(false, parameters.ordered());
        assertEquals(3, parameters.maxRetransmits());
    }

    @Test
    void anOpenListenerAddedOnceOpenRunsOnce() {
        DataProducer dataProducer = produceData();
        handler().dataChannels().getFirst().open();
        drain(transport);
        var events = new CopyOnWriteArrayList<String>();

        dataProducer.onOpen(() -> events.add("open"));
        drain(transport);

        assertEquals(List.of("open"), events);
    }
}
