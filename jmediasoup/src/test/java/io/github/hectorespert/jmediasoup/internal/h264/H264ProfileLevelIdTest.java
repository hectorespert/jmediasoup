/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.h264;

import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.generateProfileLevelIdStringForAnswer;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.isSameProfile;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.isSameProfileAndLevel;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.levelToString;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.parseProfileLevelId;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.parseSdpProfileLevelId;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.profileLevelIdToString;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.profileToString;
import static io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId.supportedLevel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of {@code src/tests/test.ts} of h264-profile-level-id 2.3.3, plus the Java-specific cases. */
class H264ProfileLevelIdTest {

    @Nested
    class ParseProfileLevelId {

        @Test
        void parseLevel() {
            assertEquals(Level.L3_1, parseProfileLevelId("42e01f").level());
            assertEquals(Level.L1_1, parseProfileLevelId("42e00b").level());
            assertEquals(Level.L1_B, parseProfileLevelId("42f00b").level());
            assertEquals(Level.L4_2, parseProfileLevelId("42C02A").level());
            assertEquals(Level.L5_2, parseProfileLevelId("640c34").level());
        }

        @Test
        void constrainedBaseline() {
            assertEquals(
                    Profile.CONSTRAINED_BASELINE, parseProfileLevelId("42e01f").profile());
            assertEquals(
                    Profile.CONSTRAINED_BASELINE, parseProfileLevelId("42C02A").profile());
            assertEquals(
                    Profile.CONSTRAINED_BASELINE, parseProfileLevelId("4de01f").profile());
            assertEquals(
                    Profile.CONSTRAINED_BASELINE, parseProfileLevelId("58f01f").profile());
        }

        @Test
        void baseline() {
            assertEquals(Profile.BASELINE, parseProfileLevelId("42a01f").profile());
            assertEquals(Profile.BASELINE, parseProfileLevelId("58A01F").profile());
        }

        @Test
        void main() {
            assertEquals(Profile.MAIN, parseProfileLevelId("4D401f").profile());
        }

        @Test
        void high() {
            assertEquals(Profile.HIGH, parseProfileLevelId("64001f").profile());
        }

        @Test
        void constrainedHigh() {
            assertEquals(Profile.CONSTRAINED_HIGH, parseProfileLevelId("640c1f").profile());
        }

        @Test
        void invalidValue() {
            // Malformed strings.
            assertNull(parseProfileLevelId(null));
            assertNull(parseProfileLevelId(""));
            assertNull(parseProfileLevelId(" 42e01f"));
            assertNull(parseProfileLevelId("4242e01f"));
            assertNull(parseProfileLevelId("e01f"));
            assertNull(parseProfileLevelId("gggggg"));
            assertNull(parseProfileLevelId("+42e01"));
            assertNull(parseProfileLevelId("-42e01"));
            // Invalid level.
            assertNull(parseProfileLevelId("42e000"));
            assertNull(parseProfileLevelId("42e00f"));
            assertNull(parseProfileLevelId("42e0ff"));
            // Invalid profile.
            assertNull(parseProfileLevelId("42e11f"));
            assertNull(parseProfileLevelId("58601f"));
            assertNull(parseProfileLevelId("64e01f"));
        }
    }

    @Nested
    class ProfileLevelIdToString {

        @Test
        void various() {
            assertEquals(
                    "42e01f", profileLevelIdToString(new ProfileLevelId(Profile.CONSTRAINED_BASELINE, Level.L3_1)));
            assertEquals("42000a", profileLevelIdToString(new ProfileLevelId(Profile.BASELINE, Level.L1)));
            assertEquals("4d001f", profileLevelIdToString(new ProfileLevelId(Profile.MAIN, Level.L3_1)));
            assertEquals("640c2a", profileLevelIdToString(new ProfileLevelId(Profile.CONSTRAINED_HIGH, Level.L4_2)));
            assertEquals("64002a", profileLevelIdToString(new ProfileLevelId(Profile.HIGH, Level.L4_2)));
        }

        @Test
        void level1b() {
            assertEquals(
                    "42f00b", profileLevelIdToString(new ProfileLevelId(Profile.CONSTRAINED_BASELINE, Level.L1_B)));
            assertEquals("42100b", profileLevelIdToString(new ProfileLevelId(Profile.BASELINE, Level.L1_B)));
            assertEquals("4d100b", profileLevelIdToString(new ProfileLevelId(Profile.MAIN, Level.L1_B)));
        }

        @Test
        void roundTrip() {
            assertEquals("42e01f", profileLevelIdToString(parseProfileLevelId("42e01f")));
            assertEquals("42e01f", profileLevelIdToString(parseProfileLevelId("42E01F")));
            assertEquals("4d100b", profileLevelIdToString(parseProfileLevelId("4d100b")));
            assertEquals("4d100b", profileLevelIdToString(parseProfileLevelId("4D100B")));
            assertEquals("640c2a", profileLevelIdToString(parseProfileLevelId("640c2a")));
            assertEquals("640c2a", profileLevelIdToString(parseProfileLevelId("640C2A")));
        }

        // The original also tests an unknown profile (255), which a Java enum cannot represent.
        @Test
        void invalidValue() {
            assertNull(profileLevelIdToString(new ProfileLevelId(Profile.HIGH, Level.L1_B)));
            assertNull(profileLevelIdToString(new ProfileLevelId(Profile.CONSTRAINED_HIGH, Level.L1_B)));
        }
    }

    @Nested
    class ParseSdpProfileLevelId {

        @Test
        void emptyValue() {
            ProfileLevelId profileLevelId = parseSdpProfileLevelId(null);

            assertEquals(Profile.CONSTRAINED_BASELINE, profileLevelId.profile());
            assertEquals(Level.L3_1, profileLevelId.level());
        }

        @Test
        void constrainedHigh() {
            ProfileLevelId profileLevelId = parseSdpProfileLevelId(Map.of("profile-level-id", "640c2a"));

            assertEquals(Profile.CONSTRAINED_HIGH, profileLevelId.profile());
            assertEquals(Level.L4_2, profileLevelId.level());
        }

        @Test
        void invalidValue() {
            assertNull(parseSdpProfileLevelId(Map.of("profile-level-id", "foobar")));
        }

        // Codec parameters keep their JSON type, and an all-digit profile-level-id may be a number.
        @Test
        void numericValue() {
            assertEquals(
                    new ProfileLevelId(Profile.HIGH, Level.L5),
                    parseSdpProfileLevelId(Map.of("profile-level-id", 640032)));
        }
    }

    @Nested
    class IsSameProfile {

        @Test
        void sameProfile() {
            assertTrue(isSameProfile(Map.of("foo", "foo"), Map.of("bar", "bar")));
            assertTrue(isSameProfile(Map.of("profile-level-id", "42e01f"), Map.of("profile-level-id", "42C02A")));
            assertTrue(isSameProfile(Map.of("profile-level-id", "42a01f"), Map.of("profile-level-id", "58A01F")));
            assertTrue(isSameProfile(Map.of("profile-level-id", "42e01f"), null));
        }

        @Test
        void notSameProfile() {
            assertFalse(isSameProfile(null, Map.of("profile-level-id", "4d001f")));
            assertFalse(isSameProfile(Map.of("profile-level-id", "42a01f"), Map.of("profile-level-id", "640c1f")));
            assertFalse(isSameProfile(Map.of("profile-level-id", "42000a"), Map.of("profile-level-id", "64002a")));
        }
    }

    @Nested
    class IsSameProfileAndLevel {

        @Test
        void sameProfileAndLevel() {
            assertTrue(isSameProfileAndLevel(Map.of("foo", "foo"), Map.of("bar", "bar")));
            assertTrue(
                    isSameProfileAndLevel(Map.of("profile-level-id", "42e01f"), Map.of("profile-level-id", "42f01f")));
            assertTrue(
                    isSameProfileAndLevel(Map.of("profile-level-id", "42a01f"), Map.of("profile-level-id", "58A01F")));
            assertTrue(isSameProfileAndLevel(Map.of("profile-level-id", "42e01f"), null));
        }

        @Test
        void notSameProfile() {
            assertFalse(isSameProfileAndLevel(null, Map.of("profile-level-id", "4d001f")));
            assertFalse(
                    isSameProfileAndLevel(Map.of("profile-level-id", "42a01f"), Map.of("profile-level-id", "640c1f")));
            assertFalse(
                    isSameProfileAndLevel(Map.of("profile-level-id", "42000a"), Map.of("profile-level-id", "64002a")));
        }
    }

    @Nested
    class GenerateProfileLevelIdStringForAnswer {

        @Test
        void emptySdpAnswer() {
            assertNull(generateProfileLevelIdStringForAnswer(null, null));
        }

        @Test
        void levelSymmetryCapped() {
            Map<String, Object> lowLevel = Map.of("profile-level-id", "42e015");
            Map<String, Object> highLevel = Map.of("profile-level-id", "42e01f");

            assertEquals("42e015", generateProfileLevelIdStringForAnswer(lowLevel, highLevel));
            assertEquals("42e015", generateProfileLevelIdStringForAnswer(highLevel, lowLevel));
        }

        @Test
        void constrainedBaselineWithLevelAsymmetry() {
            Map<String, Object> localParameters = Map.of("profile-level-id", "42e01f", "level-asymmetry-allowed", "1");
            Map<String, Object> remoteParameters = Map.of("profile-level-id", "42e015", "level-asymmetry-allowed", "1");

            assertEquals("42e01f", generateProfileLevelIdStringForAnswer(localParameters, remoteParameters));
        }

        // mediasoup sends level-asymmetry-allowed as the number 1.
        @Test
        void levelAsymmetryAsANumber() {
            Map<String, Object> localParameters = Map.of("profile-level-id", "42e01f", "level-asymmetry-allowed", 1);
            Map<String, Object> remoteParameters = Map.of("profile-level-id", "42e015", "level-asymmetry-allowed", 1L);

            assertEquals("42e01f", generateProfileLevelIdStringForAnswer(localParameters, remoteParameters));
        }

        @Test
        void invalidOrMismatchedProfilesFail() {
            assertEquals(
                    "H264 Profile mismatch",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> generateProfileLevelIdStringForAnswer(
                                            Map.of("profile-level-id", "42e01f"), Map.of("profile-level-id", "64001f")))
                            .getMessage());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> generateProfileLevelIdStringForAnswer(Map.of("profile-level-id", "foobar"), null));
        }
    }

    @Nested
    class SupportedLevel {

        @Test
        void validValues() {
            assertEquals(Level.L2_1, supportedLevel(640 * 480, 25));
            assertEquals(Level.L3_1, supportedLevel(1280 * 720, 30));
            assertEquals(Level.L4_2, supportedLevel(1920 * 1280, 60));
        }

        @Test
        void invalidValues() {
            assertNull(supportedLevel(0, 0));
            // All levels support fps > 5.
            assertNull(supportedLevel(1280 * 720, 5));
            // All levels support frame sizes > 183 * 137.
            assertNull(supportedLevel(183 * 137, 30));
        }
    }

    @Test
    void namesForHumans() {
        assertEquals("ConstrainedBaseline", profileToString(Profile.CONSTRAINED_BASELINE));
        assertEquals("PredictiveHigh444", profileToString(Profile.PREDICTIVE_HIGH_444));
        assertEquals("1b", levelToString(Level.L1_B));
        assertEquals("1", levelToString(Level.L1));
        assertEquals("3.1", levelToString(Level.L3_1));
        assertEquals("5.2", levelToString(Level.L5_2));
    }
}
