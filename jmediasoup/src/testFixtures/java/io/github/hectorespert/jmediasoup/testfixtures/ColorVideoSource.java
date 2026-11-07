/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.testfixtures;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A video source that sends frames of a single color, 320x240 at 30 frames per second, as long as it
 * is open.
 */
public final class ColorVideoSource implements AutoCloseable {

    /** The width of the frames. */
    public static final int WIDTH = 320;

    /** The height of the frames. */
    public static final int HEIGHT = 240;

    /** The frames per second. */
    public static final int FRAME_RATE = 30;

    /** The luma of the color, a bright green, in I420. */
    public static final int Y = 145;

    /** The blue-difference chroma of the color. */
    public static final int U = 54;

    /** The red-difference chroma of the color. */
    public static final int V = 34;

    private final CustomVideoSource source = new CustomVideoSource();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("color-video-source").daemon().factory());

    /** Starts sending frames. */
    public ColorVideoSource() {
        // At a fixed rate, so that a late frame does not delay the next ones.
        pusher.scheduleAtFixedRate(this::push, 0, 1_000_000 / FRAME_RATE, TimeUnit.MICROSECONDS);
    }

    /**
     * Returns the source, to create a track with {@code PeerConnectionFactory.createVideoTrack}.
     *
     * @return the source
     */
    public CustomVideoSource source() {
        return source;
    }

    /**
     * Stops sending and releases the native source. Dispose the tracks of the source before.
     *
     * @throws InterruptedException if interrupted while waiting for the last frame
     */
    @Override
    public void close() throws InterruptedException {
        pusher.shutdownNow();
        pusher.awaitTermination(1, TimeUnit.SECONDS);
        source.dispose();
    }

    private void push() {
        NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
        fill(buffer.getDataY(), Y);
        fill(buffer.getDataU(), U);
        fill(buffer.getDataV(), V);
        var frame = new VideoFrame(buffer, System.nanoTime());
        try {
            source.pushFrame(frame);
        } finally {
            frame.release();
        }
    }

    private static void fill(ByteBuffer plane, int value) {
        while (plane.hasRemaining()) {
            plane.put((byte) value);
        }
    }
}
