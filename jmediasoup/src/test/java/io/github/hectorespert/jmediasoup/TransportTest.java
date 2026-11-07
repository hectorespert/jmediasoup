/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import io.github.hectorespert.jmediasoup.fake.FakeHandler;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Ports the transport tests of mediasoup-client ({@code test.ts}) that do not produce or consume. */
class TransportTest {

    private static final ConnectHandler CONNECT = dtlsParameters -> CompletableFuture.completedFuture(null);
    private static final ProduceHandler PRODUCE = request -> CompletableFuture.completedFuture("producer-id");

    private final FakeHandler.Factory factory = FakeHandler.createFactory();
    private final Device device = new Device(factory);

    TransportTest() {
        device.load(FakeParameters.routerRtpCapabilities()).join();
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

    private FakeHandler handler(Transport transport) {
        return assertInstanceOf(FakeHandler.class, transport.handler());
    }

    private static RTCPeerConnectionIceErrorEvent iceErrorEvent() throws ReflectiveOperationException {
        Constructor<RTCPeerConnectionIceErrorEvent> constructor =
                RTCPeerConnectionIceErrorEvent.class.getDeclaredConstructor(
                        String.class, Integer.class, String.class, int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("10.0.0.1", 50000, "turn:turn.example.com", 701, "TURN host lookup failed");
    }

    @Test
    void createSendTransportSucceeds() throws Exception {
        TransportOptions options = FakeParameters.transportRemoteParameters().toBuilder()
                .appData(Map.of("baz", "BAZ"))
                .build();

        SendTransport transport = device.createSendTransport(options, CONNECT, PRODUCE);

        assertEquals(options.id(), transport.id());
        assertFalse(transport.closed());
        assertEquals(TransportDirection.SEND, transport.direction());
        assertEquals(ConnectionState.NEW, transport.connectionState());
        assertEquals(IceGatheringState.NEW, transport.iceGatheringState());
        assertEquals(Map.of("baz", "BAZ"), transport.appData());
        assertSame(factory.handlers().getFirst(), transport.handler());

        HandlerOptions handlerOptions = handler(transport).options();
        assertEquals(TransportDirection.SEND, handlerOptions.direction());
        assertEquals(options.iceParameters(), handlerOptions.iceParameters());
        assertEquals(options.iceCandidates(), handlerOptions.iceCandidates());
        assertEquals(options.dtlsParameters(), handlerOptions.dtlsParameters());
        assertEquals(options.iceServers(), handlerOptions.iceServers());
    }

    @Test
    void createRecvTransportSucceeds() throws Exception {
        TransportOptions options = FakeParameters.transportRemoteParameters();

        RecvTransport transport = device.createRecvTransport(options, CONNECT);

        assertEquals(options.id(), transport.id());
        assertEquals(TransportDirection.RECV, transport.direction());
        assertEquals(TransportDirection.RECV, handler(transport).options().direction());
        assertTrue(transport.appData().isEmpty());
    }

    @Test
    void eachTransportHasItsOwnHandler() throws Exception {
        SendTransport send = device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        RecvTransport recv = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);

        assertNotSame(send.handler(), recv.handler());
        assertEquals(2, factory.handlers().size());
    }

    @Test
    void theSctpMessageSizesOfOldServersAreFilled() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);

        SctpParameters sctpParameters = handler(transport).options().sctpParameters();
        assertEquals(new SctpParameters(5000, 1024, 1024, 2000000, 2000000, 2000000), sctpParameters);
    }

    @Test
    void createTransportFailsIfTheDeviceIsNotLoaded() {
        var unloaded = new Device(FakeHandler.createFactory());
        TransportOptions options = FakeParameters.transportRemoteParameters();

        assertThrows(InvalidStateException.class, () -> unloaded.createSendTransport(options, CONNECT, PRODUCE));
        assertThrows(InvalidStateException.class, () -> unloaded.createRecvTransport(options, CONNECT));
    }

    @Test
    void createTransportRejectsNulls() {
        TransportOptions options = FakeParameters.transportRemoteParameters();

        assertThrows(NullPointerException.class, () -> device.createSendTransport(null, CONNECT, PRODUCE));
        assertThrows(NullPointerException.class, () -> device.createSendTransport(options, null, PRODUCE));
        assertThrows(NullPointerException.class, () -> device.createSendTransport(options, CONNECT, null));
        assertThrows(NullPointerException.class, () -> device.createRecvTransport(null, CONNECT));
        assertThrows(NullPointerException.class, () -> device.createRecvTransport(options, null));
        assertTrue(factory.handlers().isEmpty());
    }

    @Test
    void aHandlerFactoryThatThrowsFailsTheCreation() throws Exception {
        var error = new IllegalStateException("no natives");
        var failing = new HandlerFactory() {
            @Override
            public String name() {
                return "FailingHandler";
            }

            @Override
            public CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction) {
                return factory.getNativeRtpCapabilities(direction);
            }

            @Override
            public CompletableFuture<SctpCapabilities> getNativeSctpCapabilities() {
                return factory.getNativeSctpCapabilities();
            }

            @Override
            public Handler create(HandlerOptions options) {
                throw error;
            }
        };
        var failingDevice = new Device(failing);
        failingDevice.load(FakeParameters.routerRtpCapabilities()).join();
        var newTransports = new AtomicInteger();
        failingDevice.observer().onNewTransport(transport -> newTransports.incrementAndGet());

        assertSame(
                error,
                assertThrows(
                        IllegalStateException.class,
                        () -> failingDevice.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT)));
        assertEquals(0, newTransports.get());
    }

    @Test
    void theDeviceObserverEmitsNewTransportOnTheExecutorOfTheTransport() throws Exception {
        var newTransport = new CompletableFuture<Transport>();
        var thread = new AtomicReference<Thread>();
        device.observer().onNewTransport(transport -> {
            thread.set(Thread.currentThread());
            newTransport.complete(transport);
        });

        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);

        assertSame(transport, await(newTransport));
        assertTrue(thread.get().isVirtual());
    }

    @Test
    void connectCallsTheHandlerOfTheApplicationOnTheExecutor() throws Exception {
        var received = new CompletableFuture<DtlsParameters>();
        var thread = new AtomicReference<Thread>();
        var signalling = new CompletableFuture<String>();
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), dtls -> {
            thread.set(Thread.currentThread());
            received.complete(dtls);
            return signalling;
        });
        DtlsParameters local = FakeParameters.localDtlsParameters();

        CompletableFuture<Void> connected =
                handler(transport).options().listener().connect(local).toCompletableFuture();

        assertSame(local, await(received));
        assertTrue(thread.get().isVirtual());
        assertFalse(connected.isDone());
        signalling.complete("any answer");
        await(connected);
    }

    @Test
    void connectFailsIfTheHandlerOfTheApplicationFails() throws Exception {
        var error = new IllegalStateException("signalling down");
        RecvTransport transport = device.createRecvTransport(
                FakeParameters.transportRemoteParameters(), dtls -> CompletableFuture.failedFuture(error));

        CompletableFuture<Void> connected = handler(transport)
                .options()
                .listener()
                .connect(FakeParameters.localDtlsParameters())
                .toCompletableFuture();

        MediasoupClientException failure = assertInstanceOf(MediasoupClientException.class, cause(connected));
        assertSame(error, failure.getCause());
    }

    @Test
    void connectFailsOnceClosed() throws Exception {
        var calls = new AtomicInteger();
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), dtls -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        transport.close();

        CompletableFuture<Void> connected = handler(transport)
                .options()
                .listener()
                .connect(FakeParameters.localDtlsParameters())
                .toCompletableFuture();

        assertInstanceOf(InvalidStateException.class, cause(connected));
        assertEquals(0, calls.get());
    }

    @Test
    void connectionStateChangesAreDeliveredOnceEach() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        List<ConnectionState> states = new CopyOnWriteArrayList<>();
        transport.onConnectionStateChange(states::add);

        handler(transport).setConnectionState(ConnectionState.CONNECTING);
        handler(transport).setConnectionState(ConnectionState.CONNECTING);
        handler(transport).setConnectionState(ConnectionState.CONNECTED);
        drain(transport);

        assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states);
        assertEquals(ConnectionState.CONNECTED, transport.connectionState());
    }

    @Test
    void iceGatheringStateChangesAreDelivered() throws Exception {
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);
        List<IceGatheringState> states = new CopyOnWriteArrayList<>();
        transport.onIceGatheringStateChange(states::add);

        handler(transport).setIceGatheringState(IceGatheringState.GATHERING);
        handler(transport).setIceGatheringState(IceGatheringState.GATHERING);
        handler(transport).setIceGatheringState(IceGatheringState.COMPLETE);
        drain(transport);

        assertEquals(List.of(IceGatheringState.GATHERING, IceGatheringState.COMPLETE), states);
        assertEquals(IceGatheringState.COMPLETE, transport.iceGatheringState());
    }

    @Test
    void iceCandidateErrorsAreDelivered() throws Exception {
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);
        var received = new CompletableFuture<RTCPeerConnectionIceErrorEvent>();
        transport.onIceCandidateError(received::complete);
        RTCPeerConnectionIceErrorEvent event = iceErrorEvent();

        handler(transport).options().listener().iceCandidateError(event);

        assertSame(event, await(received));
    }

    @Test
    void closeClosesTheHandlerAndEmitsTheObserverClose() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        var closes = new AtomicInteger();
        transport.observer().onClose(closes::incrementAndGet);
        List<ConnectionState> states = new CopyOnWriteArrayList<>();
        transport.onConnectionStateChange(states::add);

        transport.close();
        transport.close();
        drain(transport);

        assertTrue(transport.closed());
        assertTrue(handler(transport).closed());
        assertEquals(ConnectionState.CLOSED, transport.connectionState());
        assertEquals(1, closes.get());
        // The closed state is not an event.
        assertTrue(states.isEmpty());
    }

    @Test
    void noEventIsDeliveredOnceClosed() throws Exception {
        RecvTransport transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), CONNECT);
        var events = new AtomicInteger();
        transport.onConnectionStateChange(state -> events.incrementAndGet());
        transport.onIceGatheringStateChange(state -> events.incrementAndGet());
        transport.onIceCandidateError(event -> events.incrementAndGet());
        transport.close();

        handler(transport).setConnectionState(ConnectionState.CONNECTED);
        handler(transport).setIceGatheringState(IceGatheringState.GATHERING);
        handler(transport).options().listener().iceCandidateError(iceErrorEvent());
        drain(transport);

        assertEquals(0, events.get());
        assertEquals(ConnectionState.CLOSED, transport.connectionState());
    }

    @Test
    void closeFailsThePendingOperations() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        CompletableFuture<String> running = transport.queue().push("running", CompletableFuture::new);
        CompletableFuture<String> waiting =
                transport.queue().push("waiting", () -> CompletableFuture.completedFuture("never"));

        transport.close();

        assertInstanceOf(InvalidStateException.class, cause(running));
        assertInstanceOf(InvalidStateException.class, cause(waiting));
    }

    @Test
    void listenersRunOneAtATimeOnTheExecutor() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();
        var testThread = Thread.currentThread();
        var otherThread = new AtomicInteger();
        Runnable listener = () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            if (Thread.currentThread() == testThread) {
                otherThread.incrementAndGet();
            }
            Thread.yield();
            running.decrementAndGet();
        };
        transport.onConnectionStateChange(state -> listener.run());
        transport.onIceGatheringStateChange(state -> listener.run());

        for (int i = 0; i < 20; i++) {
            handler(transport).setConnectionState(i % 2 == 0 ? ConnectionState.CONNECTING : ConnectionState.CONNECTED);
            handler(transport)
                    .setIceGatheringState(i % 2 == 0 ? IceGatheringState.GATHERING : IceGatheringState.COMPLETE);
        }
        drain(transport);

        assertEquals(1, maxRunning.get());
        assertEquals(0, otherThread.get());
    }

    @Test
    void getStatsReturnsTheReportOfTheHandlerUntilClosed() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);

        assertNotNull(await(transport.getStats()));

        transport.close();

        assertInstanceOf(InvalidStateException.class, cause(transport.getStats()));
    }

    @Test
    void restartIceAndUpdateIceServersRunInTheQueueOfTheTransport() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        var release = new CompletableFuture<Void>();
        transport.queue().push("block", () -> release);

        CompletableFuture<Void> restarted = transport.restartIce(new IceParameters("new-ufrag", "new-password", null));
        CompletableFuture<Void> updated = transport.updateIceServers(List.of(IceServer.of("stun:stun.example.com")));

        assertFalse(restarted.isDone());
        assertTrue(handler(transport).iceCalls().isEmpty());

        release.complete(null);
        await(restarted);
        await(updated);

        assertEquals(
                List.of("restartIce new-ufrag", "updateIceServers [stun:stun.example.com]"),
                handler(transport).iceCalls());
    }

    @Test
    void restartIceAndUpdateIceServersFailIfTheTransportIsClosed() throws Exception {
        SendTransport transport =
                device.createSendTransport(FakeParameters.transportRemoteParameters(), CONNECT, PRODUCE);
        transport.close();

        assertInstanceOf(
                InvalidStateException.class,
                cause(transport.restartIce(new IceParameters("new-ufrag", "new-password", null))));
        assertInstanceOf(InvalidStateException.class, cause(transport.updateIceServers(List.of())));
        assertTrue(handler(transport).iceCalls().isEmpty());
    }
}
