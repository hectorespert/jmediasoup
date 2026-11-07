/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static io.github.hectorespert.jmediasoup.integration.ProtooClient.MAPPER;

import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * A broadcaster of the mediasoup demo that publishes a tone and a test pattern from ffmpeg, for the
 * tests that receive before jmediasoup can send. It creates a broadcaster peer through the REST API of
 * the demo, with a {@code PlainTransport} and a producer per stream, and runs ffmpeg in a container,
 * which sends RTP to those transports. The room has to exist: the demo creates it with the first
 * WebSocket peer.
 */
final class Broadcaster implements AutoCloseable {

    /** ffmpeg 9.0.2, built static, with libopus, libvpx and x264, pinned by digest. */
    static final DockerImageName FFMPEG_IMAGE = DockerImageName.parse(
            "docker.io/mwader/static-ffmpeg:9.0.2-1@sha256:f5d100184884eb3e82b0eb14bb5ebf98da99826ac824989720297fb9ef21c822");

    /** A stream that ffmpeg encodes and the broadcaster produces. */
    enum Stream {
        /** A 440 Hz tone, in stereo Opus. */
        OPUS(MediaKind.AUDIO, "audio", 0, """
                {
                  "codecs": [{
                    "mimeType": "audio/opus", "payloadType": 100, "clockRate": 48000, "channels": 2,
                    "parameters": {"sprop-stereo": 1}
                  }],
                  "encodings": [{"ssrc": 11111111}]
                }
                """, List.of("-c:a", "libopus", "-ac", "2", "-ar", "48000", "-b:a", "64k")),
        /** The test pattern of ffmpeg in VP8, with a key frame every second. */
        VP8(
                MediaKind.VIDEO,
                "video",
                1,
                """
                {
                  "codecs": [{"mimeType": "video/VP8", "payloadType": 101, "clockRate": 90000}],
                  "encodings": [{"ssrc": 22222222}]
                }
                """,
                List.of(
                        "-c:v",
                        "libvpx",
                        "-deadline",
                        "realtime",
                        "-cpu-used",
                        "8",
                        "-b:v",
                        "500k",
                        "-g",
                        "30",
                        "-pix_fmt",
                        "yuv420p")),
        /**
         * The test pattern in H264, Constrained Baseline, with a key frame every second that carries its
         * SPS and PPS, as the receiver only gets them in band.
         */
        H264(
                MediaKind.VIDEO,
                "video",
                1,
                """
                {
                  "codecs": [{
                    "mimeType": "video/H264", "payloadType": 102, "clockRate": 90000,
                    "parameters": {
                      "packetization-mode": 1, "profile-level-id": "42e01f", "level-asymmetry-allowed": 1
                    }
                  }],
                  "encodings": [{"ssrc": 33333333}]
                }
                """,
                List.of(
                        "-c:v",
                        "libx264",
                        "-preset",
                        "ultrafast",
                        "-tune",
                        "zerolatency",
                        "-profile:v",
                        "baseline",
                        "-g",
                        "30",
                        "-pix_fmt",
                        "yuv420p",
                        "-bsf:v",
                        "dump_extra"));

        /** The width of the test pattern. */
        static final int WIDTH = 320;

        /** The height of the test pattern. */
        static final int HEIGHT = 240;

        private final MediaKind kind;
        private final String source;
        private final int input;
        private final RtpParameters rtpParameters;
        private final List<String> encoder;

        Stream(MediaKind kind, String source, int input, String rtpParameters, List<String> encoder) {
            this.kind = kind;
            this.source = source;
            this.input = input;
            this.rtpParameters = MAPPER.readValue(rtpParameters, RtpParameters.class);
            this.encoder = encoder;
        }

        MediaKind kind() {
            return kind;
        }

        RtpParameters rtpParameters() {
            return rtpParameters;
        }
    }

    private final HttpClient http;
    private final URI baseUrl;
    private final Map<Stream, String> producerIds;
    private final GenericContainer<?> ffmpeg;

    private Broadcaster(HttpClient http, URI baseUrl, Map<Stream, String> producerIds, GenericContainer<?> ffmpeg) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.producerIds = producerIds;
        this.ffmpeg = ffmpeg;
    }

    /**
     * Joins a broadcaster to a room, produces the streams and starts sending them.
     *
     * @param server the server
     * @param roomId the room, which has to exist
     * @param peerId the broadcaster
     * @param streams the streams to produce
     * @return the broadcaster, already sending
     */
    static Broadcaster start(MediasoupServer.Server server, String roomId, String peerId, List<Stream> streams) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(ProtooClient.REQUEST_TIMEOUT)
                .build();
        URI baseUrl = URI.create(server.httpUrl() + "/rooms/" + encode(roomId) + "/broadcasters/" + encode(peerId));
        try {
            post(
                    http,
                    URI.create(server.httpUrl() + "/rooms/" + encode(roomId) + "/broadcasters"),
                    Map.of("peerId", peerId, "displayName", peerId, "device", Map.of("name", "ffmpeg")));
            // A transport per stream: with comedia, a transport only takes RTP from the first address it
            // gets, and ffmpeg sends each output from its own port.
            Map<Stream, JsonNode> transports = new EnumMap<>(Stream.class);
            for (Stream stream : streams) {
                transports.put(
                        stream,
                        post(
                                http,
                                baseUrl.resolve(baseUrl.getPath() + "/transports"),
                                Map.of("direction", "producer", "comedia", true, "rtcpMux", false)));
            }
            post(http, baseUrl.resolve(baseUrl.getPath() + "/join"), Map.of());
            Map<Stream, String> producerIds = new EnumMap<>(Stream.class);
            for (Stream stream : streams) {
                JsonNode producer = post(
                        http,
                        baseUrl.resolve(baseUrl.getPath() + "/producers"),
                        Map.of(
                                "transportId",
                                transports.get(stream).get("transportId").asString(),
                                "kind",
                                stream.kind,
                                "rtpParameters",
                                stream.rtpParameters,
                                "appData",
                                Map.of("source", stream.source)));
                producerIds.put(stream, producer.get("producerId").asString());
            }
            GenericContainer<?> ffmpeg = ffmpeg(transports);
            ffmpeg.start();
            return new Broadcaster(http, baseUrl, producerIds, ffmpeg);
        } catch (RuntimeException e) {
            http.close();
            throw e;
        }
    }

    /**
     * Returns the id of the producer of a stream.
     *
     * @param stream the stream
     * @return the producer id
     */
    String producerId(Stream stream) {
        return producerIds.get(stream);
    }

    /** Stops ffmpeg and disconnects the broadcaster. */
    @Override
    public void close() {
        try {
            ffmpeg.stop();
            http.send(
                    HttpRequest.newBuilder(baseUrl)
                            .header("Origin", MediasoupServer.Server.ORIGIN)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("cannot disconnect the broadcaster", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            http.close();
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> ffmpeg(Map<Stream, JsonNode> transports) {
        List<String> command = new ArrayList<>(List.of(
                "-hide_banner",
                "-nostdin",
                "-re",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000",
                "-re",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=" + Stream.WIDTH + "x" + Stream.HEIGHT + ":rate=30"));
        // One RTP output per stream, with the SSRC and payload type that the producer announces.
        transports.forEach((stream, transport) -> {
            var encoding = stream.rtpParameters.encodings().getFirst();
            var codec = stream.rtpParameters.codecs().getFirst();
            command.addAll(List.of("-map", stream.input + ":" + (stream.kind == MediaKind.AUDIO ? "a" : "v")));
            command.addAll(stream.encoder);
            command.addAll(List.of(
                    "-f",
                    "rtp",
                    "-ssrc",
                    String.valueOf(encoding.ssrc()),
                    "-payload_type",
                    String.valueOf(codec.payloadType()),
                    "rtp://" + transport.get("ip").asString() + ":"
                            + transport.get("port").asInt() + "?rtcpport="
                            + transport.get("rtcpPort").asInt()));
        });
        return new GenericContainer<>(FFMPEG_IMAGE)
                .withNetworkMode("host")
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withCapDrop(com.github.dockerjava.api.model.Capability.ALL))
                .withCommand(command.toArray(String[]::new))
                // ffmpeg describes each output once it has opened them all, before encoding.
                .waitingFor(Wait.forLogMessage(".*Output #" + (transports.size() - 1) + ", rtp.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)));
    }

    private static JsonNode post(HttpClient http, URI url, Object body) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(url)
                            .header("Origin", MediasoupServer.Server.ORIGIN)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "POST " + url.getPath() + " failed with " + response.statusCode() + ": " + response.body());
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            return type.startsWith("application/json") ? MAPPER.readTree(response.body()) : MAPPER.nullNode();
        } catch (IOException e) {
            throw new IllegalStateException("POST " + url.getPath() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
