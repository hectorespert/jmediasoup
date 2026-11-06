/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeMediaStreamTrackTest {

    @Test
    void worksWithoutTheNatives() {
        var audio = FakeMediaStreamTrack.audio();
        var video = FakeMediaStreamTrack.video();

        assertEquals(MediaStreamTrack.AUDIO_TRACK_KIND, audio.getKind());
        assertEquals(MediaStreamTrack.VIDEO_TRACK_KIND, video.getKind());
        assertNotEquals(audio.getId(), video.getId());
        assertEquals(MediaStreamTrackState.LIVE, audio.getState());

        audio.setEnabled(false);
        assertFalse(audio.isEnabled());
    }

    @Test
    void endingNotifiesTheListeners() {
        var track = FakeMediaStreamTrack.video();
        List<MediaStreamTrack> ended = new ArrayList<>();
        track.addTrackEndedListener(ended::add);

        track.end();

        assertEquals(MediaStreamTrackState.ENDED, track.getState());
        assertEquals(1, ended.size());
        assertSame(track, ended.get(0));
    }
}
