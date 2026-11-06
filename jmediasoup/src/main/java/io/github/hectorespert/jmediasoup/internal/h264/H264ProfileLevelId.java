/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.h264;

import io.github.hectorespert.jmediasoup.internal.ParameterValues;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The functions of h264-profile-level-id. Codec parameters are maps that keep the JSON type of each
 * value, so a {@code profile-level-id} is accepted as text or as a number.
 */
public final class H264ProfileLevelId {

    private static final Logger LOGGER = LoggerFactory.getLogger(H264ProfileLevelId.class);

    /**
     * The profile-level-id used when the parameter is missing.
     *
     * <p>The spec (RFC 6184 section 8.1) says profile Baseline and level 1, but WebRTC uses
     * ConstrainedBaseline and level 3.1 to stay compatible with older versions where external codecs
     * have no parameters (crbug/webrtc/6337).
     */
    public static final ProfileLevelId DEFAULT_PROFILE_LEVEL_ID =
            new ProfileLevelId(Profile.CONSTRAINED_BASELINE, Level.L3_1);

    // For level_idc 11 and profile_idc 0x42, 0x4D or 0x58, the constraint set3 flag tells level 1b
    // from level 1.1.
    private static final int CONSTRAINT_SET3_FLAG = 0x10;

    private static final int PIXELS_PER_MACROBLOCK = 16 * 16;

    // Three bytes in hexadecimal, without the sign that Integer.parseInt would accept.
    private static final Pattern HEX_BYTES = Pattern.compile("[0-9a-fA-F]{6}");

    // From RFC 6184 section 8.1.
    private static final List<ProfilePattern> PROFILE_PATTERNS = List.of(
            new ProfilePattern(0x42, new BitPattern("x1xx0000"), Profile.CONSTRAINED_BASELINE),
            new ProfilePattern(0x4d, new BitPattern("1xxx0000"), Profile.CONSTRAINED_BASELINE),
            new ProfilePattern(0x58, new BitPattern("11xx0000"), Profile.CONSTRAINED_BASELINE),
            new ProfilePattern(0x42, new BitPattern("x0xx0000"), Profile.BASELINE),
            new ProfilePattern(0x58, new BitPattern("10xx0000"), Profile.BASELINE),
            new ProfilePattern(0x4d, new BitPattern("0x0x0000"), Profile.MAIN),
            new ProfilePattern(0x64, new BitPattern("00000000"), Profile.HIGH),
            new ProfilePattern(0x64, new BitPattern("00001100"), Profile.CONSTRAINED_HIGH),
            new ProfilePattern(0xf4, new BitPattern("00000000"), Profile.PREDICTIVE_HIGH_444));

    // From ITU-T H.264 (02/2016) Table A-1, level limits.
    private static final List<LevelConstraint> LEVEL_CONSTRAINTS = List.of(
            new LevelConstraint(1485, 99, Level.L1),
            new LevelConstraint(1485, 99, Level.L1_B),
            new LevelConstraint(3000, 396, Level.L1_1),
            new LevelConstraint(6000, 396, Level.L1_2),
            new LevelConstraint(11880, 396, Level.L1_3),
            new LevelConstraint(11880, 396, Level.L2),
            new LevelConstraint(19800, 792, Level.L2_1),
            new LevelConstraint(20250, 1620, Level.L2_2),
            new LevelConstraint(40500, 1620, Level.L3),
            new LevelConstraint(108000, 3600, Level.L3_1),
            new LevelConstraint(216000, 5120, Level.L3_2),
            new LevelConstraint(245760, 8192, Level.L4),
            new LevelConstraint(245760, 8192, Level.L4_1),
            new LevelConstraint(522240, 8704, Level.L4_2),
            new LevelConstraint(589824, 22080, Level.L5),
            new LevelConstraint(983040, 36864, Level.L5_1),
            new LevelConstraint(2073600, 36864, Level.L5_2));

    private H264ProfileLevelId() {}

    /**
     * Parses a profile-level-id written as 3 hexadecimal bytes.
     *
     * @return the profile and level, or {@code null} if the text is not a recognized H264
     *     profile-level-id
     */
    public static @Nullable ProfileLevelId parseProfileLevelId(@Nullable String text) {
        if (text == null || !HEX_BYTES.matcher(text).matches()) {
            return null;
        }
        int numeric = Integer.parseInt(text, 16);
        if (numeric == 0) {
            return null;
        }
        int levelIdc = numeric & 0xff;
        int profileIop = (numeric >> 8) & 0xff;
        int profileIdc = (numeric >> 16) & 0xff;

        Level level;
        if (levelIdc == Level.L1_1.idc()) {
            level = (profileIop & CONSTRAINT_SET3_FLAG) != 0 ? Level.L1_B : Level.L1_1;
        } else {
            level = levelFromIdc(levelIdc);
            if (level == null) {
                LOGGER.warn("parseProfileLevelId() | unrecognized level_idc [str:{}, level_idc:{}]", text, levelIdc);
                return null;
            }
        }

        for (ProfilePattern pattern : PROFILE_PATTERNS) {
            if (profileIdc == pattern.profileIdc() && pattern.profileIop().isMatch(profileIop)) {
                LOGGER.debug(
                        "parseProfileLevelId() | result [str:{}, profile:{}, level:{}]",
                        text,
                        pattern.profile(),
                        level);
                return new ProfileLevelId(pattern.profile(), level);
            }
        }
        LOGGER.warn(
                "parseProfileLevelId() | unrecognized profile_idc/profile_iop combination [str:{},"
                        + " profile_idc:{}, profile_iop:{}]",
                text,
                profileIdc,
                profileIop);
        return null;
    }

    /**
     * Returns the canonical text of a profile-level-id, as 3 lowercase hexadecimal bytes.
     *
     * @return the text, or {@code null} if level 1b is combined with a profile that does not allow it
     */
    public static @Nullable String profileLevelIdToString(ProfileLevelId profileLevelId) {
        if (profileLevelId.level() == Level.L1_B) {
            return switch (profileLevelId.profile()) {
                case CONSTRAINED_BASELINE -> "42f00b";
                case BASELINE -> "42100b";
                case MAIN -> "4d100b";
                default -> {
                    LOGGER.warn(
                            "profileLevelIdToString() | Level 1_b not is allowed for profile {}",
                            profileLevelId.profile());
                    yield null;
                }
            };
        }
        String profileIdcIop = switch (profileLevelId.profile()) {
            case CONSTRAINED_BASELINE -> "42e0";
            case BASELINE -> "4200";
            case MAIN -> "4d00";
            case CONSTRAINED_HIGH -> "640c";
            case HIGH -> "6400";
            case PREDICTIVE_HIGH_444 -> "f400";
        };
        String level = Integer.toHexString(profileLevelId.level().idc());
        return profileIdcIop + (level.length() == 1 ? "0" + level : level);
    }

    /** Returns a human friendly name for a profile, such as {@code ConstrainedBaseline}. */
    public static String profileToString(Profile profile) {
        return switch (profile) {
            case CONSTRAINED_BASELINE -> "ConstrainedBaseline";
            case BASELINE -> "Baseline";
            case MAIN -> "Main";
            case CONSTRAINED_HIGH -> "ConstrainedHigh";
            case HIGH -> "High";
            case PREDICTIVE_HIGH_444 -> "PredictiveHigh444";
        };
    }

    /** Returns a human friendly name for a level, such as {@code 3.1} or {@code 1b}. */
    public static String levelToString(Level level) {
        if (level == Level.L1_B) {
            return "1b";
        }
        int idc = level.idc();
        return idc % 10 == 0 ? Integer.toString(idc / 10) : idc / 10 + "." + idc % 10;
    }

    /**
     * Parses the profile-level-id of SDP codec parameters.
     *
     * @return the default profile-level-id if the parameter is missing, or {@code null} if it is
     *     present but not valid
     */
    public static @Nullable ProfileLevelId parseSdpProfileLevelId(@Nullable Map<String, ?> parameters) {
        String profileLevelId = profileLevelIdParameter(parameters);
        return profileLevelId == null ? DEFAULT_PROFILE_LEVEL_ID : parseProfileLevelId(profileLevelId);
    }

    /** Returns whether two sets of codec parameters have the same H264 profile, regardless of level. */
    public static boolean isSameProfile(@Nullable Map<String, ?> parameters1, @Nullable Map<String, ?> parameters2) {
        ProfileLevelId profileLevelId1 = parseSdpProfileLevelId(parameters1);
        ProfileLevelId profileLevelId2 = parseSdpProfileLevelId(parameters2);
        return profileLevelId1 != null
                && profileLevelId2 != null
                && profileLevelId1.profile() == profileLevelId2.profile();
    }

    /** Returns whether two sets of codec parameters have the same H264 profile and level. */
    public static boolean isSameProfileAndLevel(
            @Nullable Map<String, ?> parameters1, @Nullable Map<String, ?> parameters2) {
        ProfileLevelId profileLevelId1 = parseSdpProfileLevelId(parameters1);
        ProfileLevelId profileLevelId2 = parseSdpProfileLevelId(parameters2);
        return profileLevelId1 != null && profileLevelId1.equals(profileLevelId2);
    }

    /**
     * Returns the profile-level-id to answer with in an SDP negotiation, given the local supported
     * parameters and the remote offered ones.
     *
     * <p>Both represent sendrecv media descriptions, and must have the same profile: each supported
     * profile is listed as a separate local codec and tested one at a time against the remote codec,
     * so this function does not intersect profiles. It only negotiates the level, taking
     * {@code level-asymmetry-allowed} into account.
     *
     * @return the profile-level-id, or {@code null} if neither side has one (both use the default)
     * @throws IllegalArgumentException if a profile-level-id is not valid or the profiles differ
     */
    public static @Nullable String generateProfileLevelIdStringForAnswer(
            @Nullable Map<String, ?> localSupportedParameters, @Nullable Map<String, ?> remoteOfferedParameters) {
        if (profileLevelIdParameter(localSupportedParameters) == null
                && profileLevelIdParameter(remoteOfferedParameters) == null) {
            LOGGER.warn(
                    "generateProfileLevelIdStringForAnswer() | profile-level-id missing in local and remote params");
            return null;
        }
        ProfileLevelId local = parseSdpProfileLevelId(localSupportedParameters);
        ProfileLevelId remote = parseSdpProfileLevelId(remoteOfferedParameters);
        if (local == null) {
            throw new IllegalArgumentException("invalid local_profile_level_id");
        }
        if (remote == null) {
            throw new IllegalArgumentException("invalid remote_profile_level_id");
        }
        if (local.profile() != remote.profile()) {
            throw new IllegalArgumentException("H264 Profile mismatch");
        }

        boolean levelAsymmetryAllowed =
                isLevelAsymmetryAllowed(localSupportedParameters) && isLevelAsymmetryAllowed(remoteOfferedParameters);
        // Without level asymmetry, the answer cannot upgrade the level of the offer.
        Level answerLevel = levelAsymmetryAllowed ? local.level() : minLevel(local.level(), remote.level());
        LOGGER.debug(
                "generateProfileLevelIdStringForAnswer() | result [profile:{}, level:{}]",
                local.profile(),
                answerLevel);
        return profileLevelIdToString(new ProfileLevelId(local.profile(), answerLevel));
    }

    /**
     * Returns the highest level at which a decoder that supports frames of up to a number of pixels
     * at up to a frame rate can decode every valid stream of that level.
     *
     * @return the level, or {@code null} if no level is supported
     */
    public static @Nullable Level supportedLevel(int maxFramePixelCount, double maxFps) {
        for (int i = LEVEL_CONSTRAINTS.size() - 1; i >= 0; i--) {
            LevelConstraint constraint = LEVEL_CONSTRAINTS.get(i);
            if ((long) constraint.maxMacroblockFrameSize() * PIXELS_PER_MACROBLOCK <= maxFramePixelCount
                    && constraint.maxMacroblocksPerSecond() <= maxFps * constraint.maxMacroblockFrameSize()) {
                LOGGER.debug(
                        "supportedLevel() | result [max_frame_pixel_count:{}, max_fps:{}, level:{}]",
                        maxFramePixelCount,
                        maxFps,
                        constraint.level());
                return constraint.level();
            }
        }
        LOGGER.warn(
                "supportedLevel() | no level supported [max_frame_pixel_count:{}, max_fps:{}]",
                maxFramePixelCount,
                maxFps);
        return null;
    }

    /** Reads the profile-level-id parameter as text; missing and empty values are absent. */
    private static @Nullable String profileLevelIdParameter(@Nullable Map<String, ?> parameters) {
        Object value = parameters == null ? null : parameters.get("profile-level-id");
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }

    private static boolean isLevelAsymmetryAllowed(@Nullable Map<String, ?> parameters) {
        Object value = parameters == null ? null : parameters.get("level-asymmetry-allowed");
        return Boolean.TRUE.equals(value)
                || "1".equals(value)
                || (value instanceof Number && ParameterValues.same(value, 1));
    }

    private static @Nullable Level levelFromIdc(int idc) {
        for (Level level : Level.values()) {
            // Level 1b has no level_idc of its own: it is 1.1 with the constraint set3 flag.
            if (level != Level.L1_B && level.idc() == idc) {
                return level;
            }
        }
        return null;
    }

    // Compares levels, taking level 1b into account.
    private static boolean isLessLevel(Level a, Level b) {
        if (a == Level.L1_B) {
            return b != Level.L1 && b != Level.L1_B;
        }
        if (b == Level.L1_B) {
            return a != Level.L1;
        }
        return a.idc() < b.idc();
    }

    private static Level minLevel(Level a, Level b) {
        return isLessLevel(a, b) ? a : b;
    }

    private record ProfilePattern(int profileIdc, BitPattern profileIop, Profile profile) {}

    private record LevelConstraint(int maxMacroblocksPerSecond, int maxMacroblockFrameSize, Level level) {}

    /** Matches bit patterns such as {@code x1xx0000}, where {@code x} is either 0 or 1. */
    private static final class BitPattern {

        private final int mask;
        private final int maskedValue;

        BitPattern(String pattern) {
            this.mask = ~byteMaskString('x', pattern);
            this.maskedValue = byteMaskString('1', pattern);
        }

        boolean isMatch(int value) {
            return maskedValue == (value & mask);
        }

        // Turns 8 characters into a byte with the bits set where the character is c; for c = 'x'
        // and "x1xx0000", 0b10110000.
        private static int byteMaskString(char c, String pattern) {
            int result = 0;
            for (int i = 0; i < 8; i++) {
                if (pattern.charAt(i) == c) {
                    result |= 1 << (7 - i);
                }
            }
            return result;
        }
    }
}
