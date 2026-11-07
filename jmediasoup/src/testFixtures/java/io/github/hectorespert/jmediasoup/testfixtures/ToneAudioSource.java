/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.testfixtures;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An audio source that plays a 440 Hz tone, 16-bit mono PCM at 48 kHz, in chunks of 10 ms, as long as it
 * is open. A tone, unlike silence, tells the receiver that audio arrives.
 */
public final class ToneAudioSource implements AutoCloseable {

    /** The frequency of the tone, in Hz. */
    public static final int FREQUENCY = 440;

    /** The sample rate, in Hz. */
    public static final int SAMPLE_RATE = 48_000;

    /** The peak of the tone, a quarter of the full scale of 16 bits. */
    public static final int AMPLITUDE = 8_192;

    private static final int CHUNK_MILLIS = 10;
    private static final int SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_MILLIS / 1000;

    private final CustomAudioSource source = new CustomAudioSource();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("tone-audio-source").daemon().factory());
    private long nextSample;

    /** Starts playing the tone. */
    public ToneAudioSource() {
        // At a fixed rate, so that a late chunk does not delay the next ones.
        pusher.scheduleAtFixedRate(this::push, 0, CHUNK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the source, to create a track with {@code PeerConnectionFactory.createAudioTrack}.
     *
     * @return the source
     */
    public CustomAudioSource source() {
        return source;
    }

    /**
     * Stops playing and releases the native source. Dispose the tracks of the source before.
     *
     * @throws InterruptedException if interrupted while waiting for the last chunk
     */
    @Override
    public void close() throws InterruptedException {
        pusher.shutdownNow();
        pusher.awaitTermination(1, TimeUnit.SECONDS);
        source.dispose();
    }

    private void push() {
        byte[] chunk = new byte[SAMPLES_PER_CHUNK * 2];
        for (int i = 0; i < SAMPLES_PER_CHUNK; i++, nextSample++) {
            short sample = (short) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * FREQUENCY * nextSample / SAMPLE_RATE));
            // Little endian.
            chunk[2 * i] = (byte) sample;
            chunk[2 * i + 1] = (byte) (sample >> 8);
        }
        source.pushAudio(chunk, 16, SAMPLE_RATE, 1, SAMPLES_PER_CHUNK);
    }
}
