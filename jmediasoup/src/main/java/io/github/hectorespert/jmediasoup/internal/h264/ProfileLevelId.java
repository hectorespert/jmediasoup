/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.h264;

import static java.util.Objects.requireNonNull;

/**
 * A parsed H264 {@code profile-level-id} value.
 *
 * @param profile the profile
 * @param level the level
 */
public record ProfileLevelId(Profile profile, Level level) {

    /** Checks the fields. */
    public ProfileLevelId {
        requireNonNull(profile, "profile");
        requireNonNull(level, "level");
    }
}
