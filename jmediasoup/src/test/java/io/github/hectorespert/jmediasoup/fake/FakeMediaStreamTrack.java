/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackEndedListener;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link MediaStreamTrack} that works without the webrtc-java natives, for tests with
 * {@code FakeHandler}. It overrides every method that is native or calls a native one.
 */
public final class FakeMediaStreamTrack extends MediaStreamTrack {

    private final String kind;
    private final String id = UUID.randomUUID().toString();
    private final List<MediaStreamTrackEndedListener> endedListeners = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;
    private volatile MediaStreamTrackState state = MediaStreamTrackState.LIVE;

    private FakeMediaStreamTrack(String kind) {
        this.kind = kind;
    }

    /** Returns a live, enabled audio track. */
    public static FakeMediaStreamTrack audio() {
        return new FakeMediaStreamTrack(AUDIO_TRACK_KIND);
    }

    /** Returns a live, enabled video track. */
    public static FakeMediaStreamTrack video() {
        return new FakeMediaStreamTrack(VIDEO_TRACK_KIND);
    }

    /** Ends the track and notifies the ended listeners, as when a camera is unplugged. */
    public void end() {
        state = MediaStreamTrackState.ENDED;
        endedListeners.forEach(listener -> listener.onTrackEnd(this));
    }

    /**
     * Returns how many ended listeners are registered.
     *
     * @return the number of listeners
     */
    public int endedListeners() {
        return endedListeners.size();
    }

    @Override
    public String getKind() {
        return kind;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public MediaStreamTrackState getState() {
        return state;
    }

    @Override
    public void dispose() {
        state = MediaStreamTrackState.ENDED;
    }

    @Override
    public void addTrackEndedListener(MediaStreamTrackEndedListener listener) {
        endedListeners.add(listener);
    }

    @Override
    public void removeTrackEndedListener(MediaStreamTrackEndedListener listener) {
        endedListeners.remove(listener);
    }

    @Override
    public void addTrackMuteListener(MediaStreamTrackMuteListener listener) {}

    @Override
    public void removeTrackMuteListener(MediaStreamTrackMuteListener listener) {}
}
