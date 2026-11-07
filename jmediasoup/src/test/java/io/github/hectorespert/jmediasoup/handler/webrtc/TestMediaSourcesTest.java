/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the media sources of the test fixtures, through local tracks, with the webrtc-java natives. */
class TestMediaSourcesTest {

    private static PeerConnectionFactory peerConnectionFactory;

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
    }

    @AfterAll
    static void disposePeerConnectionFactory() {
        peerConnectionFactory.dispose();
    }

    @Test
    void theVideoSourceSendsColorFramesAtItsRate() throws Exception {
        var sink = new CountingVideoSink();
        try (var source = new ColorVideoSource()) {
            VideoTrack track = peerConnectionFactory.createVideoTrack("video", source.source());
            track.addSink(sink);
            Thread.sleep(1000);
            int frames = sink.frames.get();
            track.removeSink(sink);
            track.dispose();

            assertTrue(frames >= 24 && frames <= 36, frames + " frames in a second");
            assertEquals(ColorVideoSource.WIDTH, sink.width);
            assertEquals(ColorVideoSource.HEIGHT, sink.height);
            assertEquals(ColorVideoSource.Y, sink.luma);
        }
    }

    @Test
    void theAudioSourcePlaysATone() throws Exception {
        var sink = new PeakAudioSink();
        try (var source = new ToneAudioSource()) {
            AudioTrack track = peerConnectionFactory.createAudioTrack("audio", source.source());
            long start = System.nanoTime();
            track.addSink(sink);
            Thread.sleep(1000);
            int samples = sink.samples.get();
            long elapsed = System.nanoTime() - start;
            track.removeSink(sink);
            track.dispose();

            // The rate over the time that really passed, which on a loaded machine is more than the
            // second of the sleep.
            long rate = samples * 1_000_000_000L / elapsed;
            assertTrue(
                    rate >= ToneAudioSource.SAMPLE_RATE * 8 / 10 && rate <= ToneAudioSource.SAMPLE_RATE * 12 / 10,
                    samples + " samples in " + elapsed / 1_000_000 + " ms");
            assertTrue(Math.abs(sink.peak - ToneAudioSource.AMPLITUDE) <= 1, "peak " + sink.peak);
        }
    }

    @Test
    void aClosedSourceStopsSending() throws Exception {
        var sink = new CountingVideoSink();
        var source = new ColorVideoSource();
        VideoTrack track = peerConnectionFactory.createVideoTrack("video", source.source());
        track.addSink(sink);
        Thread.sleep(200);

        source.close();
        int frames = sink.frames.get();
        Thread.sleep(200);
        track.removeSink(sink);
        track.dispose();

        assertTrue(frames > 0);
        assertEquals(frames, sink.frames.get());
    }

    private static final class CountingVideoSink implements VideoTrackSink {

        final AtomicInteger frames = new AtomicInteger();
        volatile int width;
        volatile int height;
        volatile int luma;

        @Override
        public void onVideoFrame(VideoFrame frame) {
            width = frame.buffer.getWidth();
            height = frame.buffer.getHeight();
            I420Buffer i420 = frame.buffer.toI420();
            luma = i420.getDataY().get(0) & 0xff;
            i420.release();
            frames.incrementAndGet();
        }
    }

    private static final class PeakAudioSink implements AudioTrackSink {

        final AtomicInteger samples = new AtomicInteger();
        volatile int peak;

        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            for (int i = 0; i + 1 < data.length; i += 2) {
                int sample = Math.abs((short) ((data[i] & 0xff) | (data[i + 1] << 8)));
                if (sample > peak) {
                    peak = sample;
                }
            }
            samples.addAndGet(frames);
        }
    }
}
