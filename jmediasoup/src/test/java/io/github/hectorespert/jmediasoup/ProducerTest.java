/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.fake.FakeHandler;
import io.github.hectorespert.jmediasoup.fake.FakeMediaStreamTrack;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.Priority;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ports the produce and producer tests of mediasoup-client ({@code test.ts}) and pymediasoup. */
class ProducerTest {

    private final FakeHandler.Factory factory = FakeHandler.createFactory();
    private final Device device = new Device(factory);
    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private final List<ProduceRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final SendTransport transport;

    ProducerTest() throws InvalidStateException {
        device.load(FakeParameters.routerRtpCapabilities()).join();
        transport = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> {
                    connects.add(dtls);
                    return CompletableFuture.completedFuture(null);
                },
                request -> {
                    requests.add(request);
                    return CompletableFuture.completedFuture("producer-" + nextId.getAndIncrement());
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

    private FakeHandler handler(Transport transport) {
        return assertInstanceOf(FakeHandler.class, transport.handler());
    }

    private Producer produce(FakeMediaStreamTrack track) {
        return await(transport.produce(ProducerOptions.builder(track).build()));
    }

    @Test
    void produceAudioSucceeds() {
        var connectionStates = new CopyOnWriteArrayList<ConnectionState>();
        transport.onConnectionStateChange(connectionStates::add);
        var track = FakeMediaStreamTrack.audio();

        Producer producer = await(transport.produce(
                ProducerOptions.builder(track).appData(Map.of("foo", "FOO")).build()));
        drain(transport);

        // The first produce connects the transport, as DTLS server.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.SERVER, connects.getFirst().role());
        assertEquals(List.of(ConnectionState.CONNECTING), connectionStates);

        ProduceRequest request = requests.getFirst();
        assertEquals(MediaKind.AUDIO, request.kind());
        assertEquals(Map.of("foo", "FOO"), request.appData());

        assertEquals("producer-1", producer.id());
        assertFalse(producer.closed());
        assertEquals(MediaKind.AUDIO, producer.kind());
        assertSame(track, producer.track());
        assertNull(producer.rtpSender());
        assertSame(request.rtpParameters(), producer.rtpParameters());
        assertEquals("audio/opus", producer.rtpParameters().codecs().getFirst().mimeType());
        assertFalse(producer.paused());
        assertNull(producer.maxSpatialLayer());
        assertEquals(Map.of("foo", "FOO"), producer.appData());
        assertTrue(handler(transport).localIds().contains(producer.localId()));
    }

    @Test
    void produceVideoSucceedsWithoutConnectingAgain() {
        produce(FakeMediaStreamTrack.audio());

        Producer producer = produce(FakeMediaStreamTrack.video());

        assertEquals(1, connects.size());
        assertEquals("producer-2", producer.id());
        assertEquals(MediaKind.VIDEO, producer.kind());
        assertEquals("video/VP8", producer.rtpParameters().codecs().getFirst().mimeType());
        assertEquals(2, handler(transport).localIds().size());
    }

    @Test
    void theTransportObserverEmitsNewProducer() {
        var newProducer = new CompletableFuture<Producer>();
        transport.observer().onNewProducer(newProducer::complete);

        Producer producer = produce(FakeMediaStreamTrack.audio());

        assertSame(producer, await(newProducer));
    }

    @Test
    void aDisabledTrackStartsPaused() {
        var track = FakeMediaStreamTrack.audio();
        track.setEnabled(false);

        assertTrue(produce(track).paused());
        assertFalse(await(transport.produce(ProducerOptions.builder(track)
                        .disableTrackOnPause(false)
                        .build()))
                .paused());
    }

    @Test
    void encodingsAreNormalized() {
        Producer producer = await(transport.produce(ProducerOptions.builder(FakeMediaStreamTrack.video())
                .encodings(List.of(
                        RtpEncodingParameters.builder()
                                .rid("r0")
                                .ssrc(1111L)
                                .dtx(true)
                                .maxBitrate(100000)
                                .scalabilityMode("L1T3")
                                .priority(Priority.HIGH)
                                .build(),
                        RtpEncodingParameters.builder()
                                .active(false)
                                .scaleResolutionDownBy(2.0)
                                .maxFramerate(15.0)
                                .build()))
                .build()));

        assertEquals(
                List.of(
                        RtpEncodingParameters.builder()
                                .active(true)
                                .maxBitrate(100000)
                                .scalabilityMode("L1T3")
                                .priority(Priority.HIGH)
                                .build(),
                        RtpEncodingParameters.builder()
                                .active(false)
                                .scaleResolutionDownBy(2.0)
                                .maxFramerate(15.0)
                                .build()),
                producer.rtpParameters().encodings().stream()
                        // Added by the handler.
                        .map(encoding ->
                                encoding.toBuilder().ssrc(null).rtx(null).build())
                        .toList());
    }

    @Test
    void produceFailsIfTheTransportIsClosed() {
        transport.close();

        assertInstanceOf(
                InvalidStateException.class,
                cause(transport.produce(
                        ProducerOptions.builder(FakeMediaStreamTrack.audio()).build())));
        assertTrue(requests.isEmpty());
    }

    @Test
    void produceFailsIfTheTrackHasEnded() {
        var track = FakeMediaStreamTrack.audio();
        track.end();

        assertEquals(
                "track ended",
                assertInstanceOf(
                                InvalidStateException.class,
                                cause(transport.produce(
                                        ProducerOptions.builder(track).build())))
                        .getMessage());
        assertTrue(handler(transport).localIds().isEmpty());
    }

    @Test
    void produceFailsIfTheDeviceCannotProduceTheKind() throws Exception {
        RtpCapabilities fakeNative = FakeParameters.nativeRtpCapabilities();
        var audioOnly = new RtpCapabilities(List.of(fakeNative.codecs().getFirst()), null, null);
        var audioDevice = new Device(new HandlerFactory() {
            @Override
            public String name() {
                return "AudioOnly";
            }

            @Override
            public CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction) {
                return CompletableFuture.completedFuture(audioOnly);
            }

            @Override
            public CompletableFuture<SctpCapabilities> getNativeSctpCapabilities() {
                return CompletableFuture.completedFuture(new SctpCapabilities(new NumSctpStreams(1024, 1024)));
            }

            @Override
            public Handler create(HandlerOptions options) {
                return factory.create(options);
            }
        });
        audioDevice.load(FakeParameters.routerRtpCapabilities()).join();
        SendTransport audioTransport = audioDevice.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture("id"));

        assertEquals(
                "cannot produce video",
                assertInstanceOf(
                                UnsupportedException.class,
                                cause(audioTransport.produce(ProducerOptions.builder(FakeMediaStreamTrack.video())
                                        .build())))
                        .getMessage());
    }

    @Test
    void produceFailsIfTheHandlerFails() {
        var error = new MediasoupClientException("negotiation failed");
        handler(transport).failNextOperation(error);

        assertSame(
                error,
                cause(transport.produce(
                        ProducerOptions.builder(FakeMediaStreamTrack.audio()).build())));
        assertTrue(requests.isEmpty());
        // The queue goes on.
        assertEquals("producer-1", produce(FakeMediaStreamTrack.audio()).id());
    }

    @Test
    void produceFailsAndStopsSendingIfTheProduceHandlerFails() throws Exception {
        var error = new IllegalStateException("server refused");
        SendTransport failing = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.failedFuture(error));

        MediasoupClientException failure = assertInstanceOf(
                MediasoupClientException.class,
                cause(failing.produce(
                        ProducerOptions.builder(FakeMediaStreamTrack.audio()).build())));

        assertSame(error, failure.getCause());
        assertTrue(handler(failing).localIds().isEmpty());
    }

    @Test
    void produceFailsIfTheProduceHandlerGivesAnEmptyId() throws Exception {
        SendTransport empty = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture(""));

        assertEquals(
                "produce handler completed with an empty id",
                assertInstanceOf(
                                MediasoupClientException.class,
                                cause(empty.produce(ProducerOptions.builder(FakeMediaStreamTrack.audio())
                                        .build())))
                        .getMessage());
        assertTrue(handler(empty).localIds().isEmpty());
    }

    @Test
    void produceFailsIfTheConnectHandlerFails() throws Exception {
        var error = new IllegalStateException("signalling down");
        SendTransport failing = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.failedFuture(error),
                request -> CompletableFuture.completedFuture("id"));

        MediasoupClientException failure = assertInstanceOf(
                MediasoupClientException.class,
                cause(failing.produce(
                        ProducerOptions.builder(FakeMediaStreamTrack.audio()).build())));

        assertEquals("connect handler failed", failure.getMessage());
        assertSame(error, failure.getCause());
    }

    @Test
    void closingTheTransportWhileTheServerCreatesTheProducerFailsTheProduce() throws Exception {
        var pending = new CompletableFuture<String>();
        var asked = new CompletableFuture<Void>();
        SendTransport slow = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> {
                    asked.complete(null);
                    return pending;
                });
        var newProducers = new AtomicInteger();
        slow.observer().onNewProducer(producer -> newProducers.incrementAndGet());

        CompletableFuture<Producer> result = slow.produce(
                ProducerOptions.builder(FakeMediaStreamTrack.audio()).build());
        await(asked);
        slow.close();
        pending.complete("late-id");

        assertInstanceOf(InvalidStateException.class, cause(result));
        drain(slow);
        assertEquals(0, newProducers.get());
    }

    @Test
    void productionsRunOneAtATimeInOrder() throws Exception {
        List<CompletableFuture<String>> pending = new CopyOnWriteArrayList<>();
        var asked = new LinkedBlockingQueue<MediaKind>();
        SendTransport slow = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> {
                    var id = new CompletableFuture<String>();
                    pending.add(id);
                    asked.add(request.kind());
                    return id;
                });

        CompletableFuture<Producer> audio = slow.produce(
                ProducerOptions.builder(FakeMediaStreamTrack.audio()).build());
        CompletableFuture<Producer> video = slow.produce(
                ProducerOptions.builder(FakeMediaStreamTrack.video()).build());

        assertEquals(MediaKind.AUDIO, asked.poll(5, TimeUnit.SECONDS));
        // The video production waits for the audio one.
        drain(slow);
        assertNull(asked.poll(100, TimeUnit.MILLISECONDS));
        pending.getFirst().complete("audio-id");
        assertEquals("audio-id", await(audio).id());
        assertEquals(MediaKind.VIDEO, asked.poll(5, TimeUnit.SECONDS));
        pending.get(1).complete("video-id");
        assertEquals("video-id", await(video).id());
    }

    @Test
    void closeStopsSendingAndEmitsTheObserverClose() {
        Producer producer = produce(FakeMediaStreamTrack.audio());
        var closes = new AtomicInteger();
        var transportCloses = new AtomicInteger();
        producer.observer().onClose(closes::incrementAndGet);
        producer.onTransportClose(transportCloses::incrementAndGet);

        producer.close();
        producer.close();
        // stopSending runs in the queue.
        await(transport.queue().push("after", () -> CompletableFuture.completedFuture(null)));
        drain(transport);

        assertTrue(producer.closed());
        assertFalse(handler(transport).localIds().contains(producer.localId()));
        assertEquals(1, closes.get());
        assertEquals(0, transportCloses.get());
        assertFalse(transport.closed());
    }

    @Test
    void closingTheTransportClosesItsProducers() {
        Producer producer = produce(FakeMediaStreamTrack.audio());
        List<String> events = new CopyOnWriteArrayList<>();
        producer.onTransportClose(() -> events.add("producer transportclose"));
        producer.observer().onClose(() -> events.add("producer observer close"));
        transport.observer().onClose(() -> events.add("transport observer close"));

        transport.close();
        producer.close();
        drain(transport);

        assertTrue(producer.closed());
        assertEquals(List.of("producer transportclose", "producer observer close", "transport observer close"), events);
    }

    @Test
    void produceRejectsNullOptions() {
        assertThrows(NullPointerException.class, () -> transport.produce(null));
    }
}
