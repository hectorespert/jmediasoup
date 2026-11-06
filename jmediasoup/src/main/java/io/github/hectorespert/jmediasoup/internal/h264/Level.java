/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.h264;

/** Supported H264 levels, with their {@code level_idc} value. */
public enum Level {
    L1_B(0),
    L1(10),
    L1_1(11),
    L1_2(12),
    L1_3(13),
    L2(20),
    L2_1(21),
    L2_2(22),
    L3(30),
    L3_1(31),
    L3_2(32),
    L4(40),
    L4_1(41),
    L4_2(42),
    L5(50),
    L5_1(51),
    L5_2(52);

    private final int idc;

    Level(int idc) {
        this.idc = idc;
    }

    /** Returns the {@code level_idc} value, which is 0 for level 1b. */
    public int idc() {
        return idc;
    }
}
