/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.hectorespert.jmediasoup.ConsumerOptions;
import io.github.hectorespert.jmediasoup.DataConsumerOptions;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every fixture is read, validated and written again without losing data (acceptance of phase 1). */
class FixturesRoundTripTest {

    // Each fixture file is read as the type of the first prefix that matches its name.
    private static final Map<String, Class<?>> TYPES = Map.of(
            "router-rtp-capabilities", RtpCapabilities.class,
            "native-rtp-capabilities", RtpCapabilities.class,
            "transport-", TransportOptions.class,
            "consumer-", ConsumerOptions.class,
            "data-consumer-", DataConsumerOptions.class);

    @WithEachJackson
    void everyFixtureSurvivesTheRoundTrip(JsonCodec json) throws Exception {
        List<Path> fixtures = fixtures();
        assertEquals(7, fixtures.size(), fixtures::toString);

        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString().replace(".json", "");
            String input = Files.readString(fixture);
            Object model = json.read(input, typeOf(name));
            String output = json.write(model);

            assertEquals(model, json.read(output, typeOf(name)), name);
            assertContained(json.read(input, Object.class), json.read(output, Object.class), name);
        }
    }

    @Test
    void fakeParametersReturnNewIds() {
        TransportOptions transport = FakeParameters.transportRemoteParameters();
        ConsumerOptions consumer = FakeParameters.consumerRemoteParameters("video/VP8");
        DataConsumerOptions dataConsumer = FakeParameters.dataConsumerRemoteParameters("given-id");

        assertNotEquals(
                transport.id(), FakeParameters.transportRemoteParameters().id());
        assertNotEquals("consumer-id", consumer.id());
        assertNotEquals("producer-id", consumer.producerId());
        assertEquals("given-id", dataConsumer.id());
        assertEquals(1024, transport.sctpParameters().os());
        assertEquals(
                99991112L, consumer.rtpParameters().encodings().get(0).rtx().ssrc());
    }

    @Test
    void portedFixturesFixTheBugsOfTheOriginal() {
        // ISAC used the variable id as the key of its first header extension.
        assertEquals(
                1,
                FakeParameters.consumerRemoteParameters("audio/ISAC")
                        .rtpParameters()
                        .headerExtensions()
                        .get(0)
                        .id());
        // pydantic made the stream ordered, which contradicts maxPacketLifeTime.
        assertNull(FakeParameters.dataConsumerRemoteParameters()
                .sctpStreamParameters()
                .ordered());
    }

    private static List<Path> fixtures() throws Exception {
        Path directory = Path.of(FakeParameters.class.getResource("/fixtures").toURI());
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static Class<?> typeOf(String name) {
        return TYPES.entrySet().stream()
                .filter(entry -> name.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no type for fixture " + name));
    }

    /** Checks that every value of the input is in the output; the output may add empty collections. */
    private static void assertContained(Object input, Object output, String path) {
        if (input instanceof Map<?, ?> inputMap) {
            if (!(output instanceof Map<?, ?> outputMap)) {
                fail(path + " is no longer an object: " + output);
                return;
            }
            inputMap.forEach((key, value) -> {
                assertTrue(outputMap.containsKey(key), path + "." + key + " was lost");
                assertContained(value, outputMap.get(key), path + "." + key);
            });
        } else if (input instanceof List<?> inputList) {
            if (!(output instanceof List<?> outputList) || inputList.size() != outputList.size()) {
                fail(path + " changed size: " + output);
                return;
            }
            for (int i = 0; i < inputList.size(); i++) {
                assertContained(inputList.get(i), outputList.get(i), path + "[" + i + "]");
            }
        } else {
            assertEquals(input, output, path);
        }
    }
}
