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

import io.github.hectorespert.jmediasoup.fake.FakeHandler;
import io.github.hectorespert.jmediasoup.fake.FakeMediaStreamTrack;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ports the consume and consumer tests of mediasoup-client ({@code test.ts}) and pymediasoup. */
class ConsumerTest {

    private final Device device = new Device(FakeHandler.createFactory());
    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private final RecvTransport transport;

    ConsumerTest() throws InvalidStateException {
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

    // Waits until the operations pushed before have completed.
    private static void flush(Transport transport) {
        await(transport.queue().push("flush", () -> CompletableFuture.completedFuture(null)));
        drain(transport);
    }

    private FakeHandler handler(Transport transport) {
        return assertInstanceOf(FakeHandler.class, transport.handler());
    }

    private Consumer consume(String codecMimeType) {
        return await(transport.consume(FakeParameters.consumerRemoteParameters(codecMimeType)));
    }

    // Holds the queue of the transport until the returned future completes.
    private static CompletableFuture<Void> block(Transport transport) {
        var release = new CompletableFuture<Void>();
        transport.queue().push("block", () -> release);
        return release;
    }

    @Test
    void consumeAudioSucceeds() {
        ConsumerOptions options = new ConsumerOptions(
                "consumer-id",
                "producer-id",
                MediaKind.AUDIO,
                FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters(),
                null,
                Map.of("bar", "BAR"));

        Consumer consumer = await(transport.consume(options));

        // The first consume connects the transport, as DTLS client.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.CLIENT, connects.getFirst().role());

        assertEquals("consumer-id", consumer.id());
        assertEquals("producer-id", consumer.producerId());
        assertFalse(consumer.closed());
        assertEquals(MediaKind.AUDIO, consumer.kind());
        assertEquals("audio", consumer.track().getKind());
        assertNull(consumer.rtpReceiver());
        assertSame(options.rtpParameters(), consumer.rtpParameters());
        assertFalse(consumer.paused());
        assertEquals(Map.of("bar", "BAR"), consumer.appData());
        assertTrue(handler(transport).localIds().contains(consumer.localId()));
    }

    // Ports the checks of test_consume in pymediasoup, whose fixtures FakeParameters uses, without the
    // defaults that pydantic fills (an empty rtcpFeedback parameter, encrypt false): they stay null,
    // and the server fills them.
    @Test
    void theRtpParametersAreThoseOfPymediasoup() {
        Consumer audio = consume("audio/opus");
        Consumer video = consume("video/VP8");

        assertNull(audio.rtpParameters().mid());
        ProducerTest.assertJson("""
                [{"mimeType": "audio/opus", "payloadType": 100, "clockRate": 48000, "channels": 2,
                  "rtcpFeedback": [{"type": "transport-cc"}],
                  "parameters": {"useinbandfec": 1, "foo": "bar"}}]""", audio.rtpParameters().codecs());
        ProducerTest.assertJson("""
                [{"uri": "urn:ietf:params:rtp-hdrext:sdes:mid", "id": 1, "parameters": {}},
                 {"uri": "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01", "id": 5,
                  "parameters": {}},
                 {"uri": "urn:ietf:params:rtp-hdrext:ssrc-audio-level", "id": 10,
                  "parameters": {}}]""", audio.rtpParameters().headerExtensions());
        assertEquals(1, audio.rtpParameters().encodings().size());
        assertFalse(audio.paused());

        assertNull(video.rtpParameters().mid());
        ProducerTest.assertJson("""
                [{"mimeType": "video/VP8", "payloadType": 101, "clockRate": 90000,
                  "rtcpFeedback": [{"type": "nack"}, {"type": "nack", "parameter": "pli"},
                                   {"type": "ccm", "parameter": "fir"}, {"type": "goog-remb"},
                                   {"type": "transport-cc"}],
                  "parameters": {"x-google-start-bitrate": 1500}},
                 {"mimeType": "video/rtx", "payloadType": 102, "clockRate": 90000, "rtcpFeedback": [],
                  "parameters": {"apt": 101}}]""", video.rtpParameters().codecs());
        ProducerTest.assertJson("""
                [{"uri": "urn:ietf:params:rtp-hdrext:sdes:mid", "id": 1, "parameters": {}},
                 {"uri": "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "id": 4,
                  "parameters": {}},
                 {"uri": "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01", "id": 5,
                  "parameters": {}},
                 {"uri": "urn:3gpp:video-orientation", "id": 11, "parameters": {}},
                 {"uri": "urn:ietf:params:rtp-hdrext:toffset", "id": 12, "parameters": {}}]""", video.rtpParameters().headerExtensions());
        assertEquals(1, video.rtpParameters().encodings().size());
        assertFalse(video.paused());
    }

    @Test
    void consumeVideoSucceedsWithoutConnectingAgain() {
        consume("audio/opus");

        Consumer consumer = consume("video/VP8");

        assertEquals(1, connects.size());
        assertEquals(MediaKind.VIDEO, consumer.kind());
        assertEquals("video", consumer.track().getKind());
        flush(transport);
        // And the consumer for RTP probation.
        assertEquals(3, handler(transport).localIds().size());
    }

    @Test
    void theTransportObserverEmitsNewConsumer() {
        var newConsumer = new CompletableFuture<Consumer>();
        transport.observer().onNewConsumer(newConsumer::complete);

        Consumer consumer = consume("audio/opus");

        assertSame(consumer, await(newConsumer));
    }

    @Test
    void consumesThatArriveTogetherAreReceivedInOneBatch() {
        CompletableFuture<Void> release = block(transport);

        List<CompletableFuture<Consumer>> results = List.of(
                transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")),
                transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")),
                transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
        release.complete(null);

        List<Consumer> consumers = results.stream().map(ConsumerTest::await).toList();
        assertEquals(
                List.of(MediaKind.AUDIO, MediaKind.VIDEO, MediaKind.AUDIO),
                consumers.stream().map(Consumer::kind).toList());
        flush(transport);
        // The video consumer creates the consumer for RTP probation after the batch.
        assertEquals(List.of(3, 1), handler(transport).receiveBatches());
    }

    @Test
    void consumesThatArriveDuringABatchGoInTheNextOne() {
        var connect = new CompletableFuture<Void>();
        var asked = new CompletableFuture<Void>();
        RecvTransport slow = createRecvTransport(dtls -> {
            asked.complete(null);
            return connect;
        });

        CompletableFuture<Consumer> first = slow.consume(FakeParameters.consumerRemoteParameters("audio/opus"));
        // The first batch waits for the connection.
        await(asked);
        CompletableFuture<Consumer> second = slow.consume(FakeParameters.consumerRemoteParameters("audio/opus"));
        CompletableFuture<Consumer> third = slow.consume(FakeParameters.consumerRemoteParameters("video/VP8"));
        connect.complete(null);

        await(first);
        await(second);
        await(third);
        flush(slow);
        // The video consumer creates the consumer for RTP probation after its batch.
        assertEquals(List.of(1, 2, 1), handler(slow).receiveBatches());
    }

    @Test
    void consumesFromManyThreadsAllSucceedInBatches() throws Exception {
        List<Consumer> consumers;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Consumer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String codec = i % 2 == 0 ? "audio/opus" : "video/VP8";
                futures.add(
                        threads.submit(() -> await(transport.consume(FakeParameters.consumerRemoteParameters(codec)))));
            }
            consumers = new ArrayList<>();
            for (Future<Consumer> future : futures) {
                consumers.add(future.get(5, TimeUnit.SECONDS));
            }
        }

        flush(transport);
        assertEquals(20, consumers.stream().map(Consumer::localId).distinct().count());
        // And the consumer for RTP probation.
        assertEquals(
                21,
                handler(transport).receiveBatches().stream()
                        .mapToInt(Integer::intValue)
                        .sum());
    }

    @Test
    void consumeFailsIfTheTransportIsClosed() {
        transport.close();

        assertInstanceOf(
                InvalidStateException.class,
                cause(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus"))));
        assertTrue(handler(transport).receiveBatches().isEmpty());
    }

    @Test
    void consumeFailsIfTheDeviceCannotReceiveIt() {
        assertEquals(
                "cannot consume this Producer",
                assertInstanceOf(
                                UnsupportedException.class,
                                cause(transport.consume(FakeParameters.consumerRemoteParameters("audio/ISAC"))))
                        .getMessage());
    }

    @Test
    void aFailedBatchFailsAllItsConsumesAndTheNextOnesGoOn() {
        var error = new MediasoupClientException("negotiation failed");
        handler(transport).failNextOperation(error);
        CompletableFuture<Void> release = block(transport);

        CompletableFuture<Consumer> first = transport.consume(FakeParameters.consumerRemoteParameters("audio/opus"));
        CompletableFuture<Consumer> second = transport.consume(FakeParameters.consumerRemoteParameters("video/VP8"));
        release.complete(null);

        assertSame(error, cause(first));
        assertSame(error, cause(second));
        assertEquals("video", consume("video/VP8").track().getKind());
    }

    @Test
    void closeStopsReceivingAndEmitsTheObserverClose() {
        Consumer consumer = consume("audio/opus");
        var closes = new AtomicInteger();
        var transportCloses = new AtomicInteger();
        consumer.observer().onClose(closes::incrementAndGet);
        consumer.onTransportClose(transportCloses::incrementAndGet);

        consumer.close();
        consumer.close();
        flush(transport);

        assertTrue(consumer.closed());
        assertFalse(handler(transport).localIds().contains(consumer.localId()));
        assertEquals(1, closes.get());
        assertEquals(0, transportCloses.get());
    }

    @Test
    void consumersClosedTogetherAreStoppedInOneBatch() {
        Consumer audio = consume("audio/opus");
        Consumer video = consume("video/VP8");
        Consumer other = consume("audio/opus");
        CompletableFuture<Void> release = block(transport);

        audio.close();
        video.close();
        other.close();
        release.complete(null);
        flush(transport);

        assertEquals(List.of(3), handler(transport).stopReceivingBatches());
        // Only the consumer for RTP probation remains.
        assertEquals(1, handler(transport).localIds().size());
    }

    @Test
    void pauseAndResumeDisableAndEnableTheTrackAndEmitTheObserverEvents() {
        Consumer consumer = consume("video/VP8");
        List<String> events = new CopyOnWriteArrayList<>();
        consumer.observer().onPause(() -> events.add("pause"));
        consumer.observer().onResume(() -> events.add("resume"));

        consumer.pause();
        flush(transport);

        assertTrue(consumer.paused());
        assertFalse(consumer.track().isEnabled());
        assertEquals(
                List.of("pauseReceiving [" + consumer.localId() + "]"),
                handler(transport).pauseCalls());

        consumer.resume();
        flush(transport);

        assertFalse(consumer.paused());
        assertTrue(consumer.track().isEnabled());
        assertEquals(List.of("pause", "resume"), events);
        assertEquals(
                List.of("pauseReceiving [" + consumer.localId() + "]", "resumeReceiving [" + consumer.localId() + "]"),
                handler(transport).pauseCalls());
    }

    @Test
    void pausingTwiceOrResumingWhatIsNotPausedDoesNothing() {
        Consumer consumer = consume("audio/opus");
        var events = new AtomicInteger();
        consumer.observer().onPause(events::incrementAndGet);
        consumer.observer().onResume(events::incrementAndGet);

        consumer.resume();
        consumer.pause();
        consumer.pause();
        flush(transport);

        assertTrue(consumer.paused());
        assertEquals(1, events.get());
        assertEquals(
                List.of("pauseReceiving [" + consumer.localId() + "]"),
                handler(transport).pauseCalls());
    }

    @Test
    void consumersPausedTogetherArePausedInOneBatch() {
        Consumer audio = consume("audio/opus");
        Consumer video = consume("video/VP8");
        CompletableFuture<Void> release = block(transport);

        audio.pause();
        video.pause();
        release.complete(null);
        flush(transport);

        assertEquals(
                List.of("pauseReceiving [" + audio.localId() + ", " + video.localId() + "]"),
                handler(transport).pauseCalls());
    }

    @Test
    void aResumeBeforeThePauseRunsCancelsIt() {
        Consumer audio = consume("audio/opus");
        Consumer video = consume("video/VP8");
        CompletableFuture<Void> release = block(transport);

        audio.pause();
        video.pause();
        audio.resume();
        release.complete(null);
        flush(transport);

        assertFalse(audio.paused());
        assertEquals(
                List.of("pauseReceiving [" + video.localId() + "]", "resumeReceiving [" + audio.localId() + "]"),
                handler(transport).pauseCalls());
    }

    @Test
    void closingAPausedConsumerBeforeThePauseRunsOnlyClosesIt() {
        Consumer consumer = consume("audio/opus");
        CompletableFuture<Void> release = block(transport);

        consumer.pause();
        consumer.close();
        release.complete(null);
        flush(transport);

        assertTrue(handler(transport).pauseCalls().isEmpty());
        assertEquals(List.of(1), handler(transport).stopReceivingBatches());
    }

    @Test
    void aFailedPauseOfTheHandlerIsOnlyLogged() {
        Consumer consumer = consume("audio/opus");
        handler(transport).failNextOperation(new MediasoupClientException("pause failed"));

        consumer.pause();
        flush(transport);

        assertTrue(consumer.paused());
        assertFalse(consumer.track().isEnabled());
        // The transport keeps working.
        consume("video/VP8");
    }

    @Test
    void pauseAndResumeDoNothingOnAClosedConsumer() {
        Consumer consumer = consume("audio/opus");
        var events = new AtomicInteger();
        consumer.observer().onPause(events::incrementAndGet);
        consumer.close();

        consumer.pause();
        flush(transport);

        assertFalse(consumer.paused());
        assertTrue(consumer.track().isEnabled());
        assertEquals(0, events.get());
        assertTrue(handler(transport).pauseCalls().isEmpty());
    }

    @Test
    void theEndOfTheTrackIsEmittedUntilTheConsumerCloses() {
        Consumer consumer = consume("video/VP8");
        var track = assertInstanceOf(FakeMediaStreamTrack.class, consumer.track());
        List<String> events = new CopyOnWriteArrayList<>();
        consumer.onTrackEnded(() -> events.add("trackended"));
        consumer.observer().onTrackEnded(() -> events.add("observer trackended"));

        track.end();
        drain(transport);

        assertEquals(List.of("trackended", "observer trackended"), events);
        assertFalse(consumer.closed());

        consumer.close();

        assertEquals(0, track.endedListeners());
    }

    @Test
    void theFirstVideoConsumerCreatesTheConsumerForRtpProbationOnce() {
        consume("audio/opus");
        flush(transport);

        assertEquals(List.of(1), handler(transport).receiveBatches());

        consume("video/VP8");
        consume("video/VP8");
        flush(transport);

        assertEquals(List.of(1, 1, 1, 1), handler(transport).receiveBatches());
        assertEquals(3 + 1, handler(transport).localIds().size());
    }

    @Test
    void aFailedConsumerForRtpProbationIsOnlyLoggedAndTriedAgain() {
        handler(transport).failNextReceive("probator", new MediasoupClientException("probator failed"));
        Consumer first = consume("video/VP8");
        flush(transport);

        assertFalse(first.closed());

        consume("video/VP8");
        flush(transport);

        // The next video consumer creates it: video, failed probator, video and probator.
        assertEquals(List.of(1, 1, 1, 1), handler(transport).receiveBatches());
        assertEquals(2 + 1, handler(transport).localIds().size());
    }

    @Test
    void getStatsReturnsTheReportOfTheReceiverUntilClosed() {
        Consumer consumer = consume("audio/opus");
        Consumer other = consume("audio/opus");

        assertNotNull(await(consumer.getStats()));

        consumer.close();

        assertInstanceOf(InvalidStateException.class, cause(consumer.getStats()));

        transport.close();

        assertInstanceOf(InvalidStateException.class, cause(other.getStats()));
    }

    @Test
    void closingTheTransportClosesItsConsumers() {
        Consumer consumer = consume("audio/opus");
        List<String> events = new CopyOnWriteArrayList<>();
        consumer.onTransportClose(() -> events.add("consumer transportclose"));
        consumer.observer().onClose(() -> events.add("consumer observer close"));
        transport.observer().onClose(() -> events.add("transport observer close"));

        transport.close();
        consumer.close();
        drain(transport);

        assertTrue(consumer.closed());
        assertEquals(List.of("consumer transportclose", "consumer observer close", "transport observer close"), events);
        assertTrue(handler(transport).stopReceivingBatches().isEmpty());
    }

    @Test
    void closingTheTransportFailsThePendingConsumes() {
        block(transport);
        CompletableFuture<Consumer> pending = transport.consume(FakeParameters.consumerRemoteParameters("audio/opus"));

        transport.close();

        assertInstanceOf(InvalidStateException.class, cause(pending));
    }

    @Test
    void closingTheTransportWhileReceivingFailsTheConsumes() {
        var connect = new CompletableFuture<Void>();
        var asked = new CompletableFuture<Void>();
        RecvTransport slow = createRecvTransport(dtls -> {
            asked.complete(null);
            return connect;
        });
        var newConsumers = new AtomicInteger();
        slow.observer().onNewConsumer(consumer -> newConsumers.incrementAndGet());

        CompletableFuture<Consumer> result = slow.consume(FakeParameters.consumerRemoteParameters("audio/opus"));
        await(asked);
        slow.close();

        assertInstanceOf(InvalidStateException.class, cause(result));
        connect.complete(null);
        drain(slow);
        assertEquals(0, newConsumers.get());
    }

    @Test
    void consumeRejectsNullOptions() {
        assertThrows(NullPointerException.class, () -> transport.consume(null));
    }

    private RecvTransport createRecvTransport(ConnectHandler connect) {
        try {
            return device.createRecvTransport(FakeParameters.transportRemoteParameters(), connect);
        } catch (InvalidStateException e) {
            throw new IllegalStateException(e);
        }
    }
}
