# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Implement a Java client for [mediasoup](https://mediasoup.org), the counterpart of what [pymediasoup](https://github.com/Huddle01/pymediasoup) is for Python. pymediasoup models its API on the official JavaScript client (`mediasoup-client`) and passes an aiortc-based handler into its `Device`, so use both projects as the reference for API shape and behaviour.

## Roadmap

[TODO.md](TODO.md) holds the plan to reach an MVP and then parity with pymediasoup. It is a living document: phases, tasks and steps can change as the implementation progresses, so update it instead of following it to the letter when something turns out to be wrong.

## Commits

Write commit messages as [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`, with the summary in the imperative and lowercase, and the scope optional. Use `feat` for new library functionality, `fix` for bug fixes, `test`, `docs`, `build` (Gradle, dependencies), `ci` (workflows, Dependabot), `refactor` and `chore`. Scopes name the area touched, such as `rtp`, `sctp`, `transport`, `event` or `ortc`. Mark breaking changes with `!` and a `BREAKING CHANGE:` footer.

Credit Claude with an `Assisted-by:` trailer instead of `Co-Authored-By:`, naming the model that did the work:

```
Assisted-by: Claude Opus 5.5 <noreply@anthropic.com>
```

## Project state

Phases 1 (foundations), 2 (ORTC), 3 (SDP) and 4 (Device and Transport with `FakeHandler`) are done. `Device` loads, reports its capabilities and creates transports, which connect, report their state, produce and consume (in batches, as mediasoup-client 3.26 does). Producers and consumers pause and resume (section 8.1), the consumers in batches too, and report the end of their track; producers replace their track (8.2). The receive transport creates the consumer for RTP probation with the first video consumer (8.3). Transports, producers and consumers give the stats of webrtc-java, and transports restart ICE and update their ICE servers (Phase 10). The library never releases a track, whatever `stopTracks` says: the application does (`8.2.13`). The webrtc-java handler (Phases 5 and 6) gives the native capabilities, receives and sends: against the mediasoup server of the integration tests, it receives Opus, VP8 and H264 that ffmpeg publishes, and sends the tone and the frames of the test sources in Opus, VP8 and H264. It does not support simulcast or codec options yet. The module `io.github.hectorespert.jmediasoup` exports these packages, all `@NullMarked`:

- the root package: `Device`; the transports, a sealed `Transport` with `SendTransport` and `RecvTransport`, which run every listener and handler of the application on their own serial executor; the handlers the application passes when creating them (`ConnectHandler`, `ProduceHandler` with `ProduceRequest`); `Producer` and `Consumer`; `DeviceObserver`, `TransportObserver`, `ProducerObserver` and `ConsumerObserver`; the exceptions (`MediasoupClientException`, a checked base, with `UnsupportedException` and `InvalidStateException`), `Subscription`, `DataChannelMessage`, and the producer and consumer options;
- `rtp`, `sctp` and `transport`: the models, as validated, immutable records;
- `handler`: the contract with the WebRTC stack for custom handlers, the one of mediasoup-client 3.26: `HandlerFactory` (native capabilities by direction, `create`), `Handler`, which reports to its transport through the `HandlerListener` in its `HandlerOptions`, their options and results, and `DataChannel`;
- `handler.webrtc`: `WebRtcJavaHandler`, the handler for webrtc-java, and its `Factory`, which uses a `PeerConnectionFactory` of the application or creates its own.

Internal packages go under `io.github.hectorespert.jmediasoup.internal` and are not exported. `internal.Checks` validates the model constructors, `internal.ParameterValues` compares codec parameters, `internal.h264` is the port of h264-profile-level-id, `internal.ortc` is the port of `ortc.py` (following mediasoup-client where they differ), `internal.sdptransform` is the port of sdp-transform, whose parsed SDP is a tree of mutable maps and lists, `internal.sdp` holds the SDP handling of mediasoup-client on top of it, and `internal.event` delivers events to listeners, calls the user's asynchronous handlers (`connect`, `produce`) and serializes the work of a transport: `SerialExecutor` runs the user's code one task at a time on virtual threads, and `OperationQueue` chains the operations like the `AwaitQueue` of mediasoup-client. Use it for every event. The Javadoc task leaves the internal packages out, patching their compiled classes into the module so that the public sources still resolve. It links the JDK and webrtc-java Javadoc online, so it needs network, and any Javadoc warning fails the build.

Models that travel as JSON carry Jackson annotations and no runtime JSON code: `@JsonInclude(NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown = true)` on each record, `@JsonProperty(required = true)` on required primitive components, and `@JsonValue`/`@JsonCreator` on the `value()`/`fromValue()` of each enum. Test them with both Jackson 2 and 3 through `json.WithEachJackson`.

Tests that send real media use the sources of the test fixtures (`src/testFixtures`, module `io.github.hectorespert.jmediasoup.testfixtures`): `ToneAudioSource` and `ColorVideoSource`, which push a tone and green frames to the custom sources of webrtc-java. They are shared by `test` and `integrationTest` and are not published. Tests that need a track, a data channel or a handler use `fake.FakeMediaStreamTrack`, `fake.FakeDataChannel` and `fake.FakeHandler` (the port of mediasoup-client's), test-only implementations that work without the webrtc-java natives. `fake.FakeParameters` returns what a mediasoup server would send, loaded from the JSON files in `src/test/resources/fixtures/`.

The WebRTC stack is [webrtc-java](https://github.com/devopvoid/webrtc-java); the results of the spike that validated it, and what they mean for the design, are in section 0.3 of TODO.md.

The design decisions (async model, threading, events, JSON, nullness, logging, JPMS, SDP representation, dependency scopes) are recorded in the "Decisions" section of TODO.md. Follow them, and ask before departing from one.

## Commands

Use the Gradle wrapper (Gradle 9.8.2). There is one subproject, `jmediasoup`.

```bash
./gradlew build                  # compile, test and assemble the jar
./gradlew test                   # run all tests
./gradlew :jmediasoup:test --tests 'io.github.hectorespert.jmediasoup.SmokeTest.testsRunInTheRootPackage'   # run a single test
./gradlew clean
./gradlew publishToMavenLocal      # publish io.github.hectorespert:jmediasoup to ~/.m2
./gradlew publishAggregationToCentralPortal   # sign and upload to Maven Central (needs the signing key and the portal token)
./gradlew spotlessApply          # format all files; `build` fails through spotlessCheck if any is not formatted
./gradlew integrationTest        # tests against a mediasoup server in a container; `check` and `build` run them too
```

The integration tests (`src/integrationTest`) start the mediasoup demo image of section 0.4 of TODO.md with Testcontainers, on the host network, and run on the classpath. Without Docker, or outside Linux, they are skipped. With podman, start its socket (`systemctl --user start podman.socket`) and run Gradle with `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock`. The server logs go to the report of a failed test. The tests talk to the server through `DemoPeer`, which has a method for each request of the protoo signaling of the demo (`ProtooClient`) and returns the jmediasoup models, and the media to receive comes from ffmpeg, in a second container, through `Broadcaster`. `LoopbackTest`, the MVP end to end, has one jmediasoup peer send the test sources and another one, in the same process, receive them. `RecyclingTest` does the same to check that the receive transport reuses the sections of closed consumers. `TurnTest` sends through coturn, in a third container.

Spotless formats Java with palantir-java-format and adds the SPDX licence header, formats the `.gradle.kts` scripts with ktlint, and trims trailing whitespace in Markdown, YAML and TOML. Run `spotlessApply` after editing. Test reports are written to `jmediasoup/build/reports/tests/test/` and `jmediasoup/build/reports/tests/integrationTest/`.

## Build setup

- Java 21 through a Gradle toolchain (`jmediasoup/build.gradle.kts`). The foojay resolver plugin downloads a matching JDK if none is installed.
- Tests use JUnit Jupiter 6. The `org.gradlex.java-module-testing` plugin runs them inside the library module (whitebox, `--patch-module`), so they can use the internal packages; a module a test needs has to be added to `requires` in the `javaModuleTesting.whitebox` block.
- Declare dependency versions in the version catalog, `gradle/libs.versions.toml`, and reference them as `libs.*` from the build script.
- The `java-library` plugin is applied, so `api` dependencies are exposed to consumers and `implementation` dependencies are not.
- The configuration cache is on (`gradle.properties`), so custom tasks and build logic have to be compatible with it.
- The version comes from git, through the axion-release plugin in the root build script: an `X.Y.Z` tag, without prefix as the tags of the GitHub releases, gives `X.Y.Z`, and any other commit the next minor version of the last tag with `-SNAPSHOT`. Any tag that is not SemVer fails the build. On CI (`CI=true`) the plugin fetches the full history of a shallow clone. `./gradlew currentVersion` prints it.
- Publishing signs with the armored key in the `signingInMemoryKey` and `signingInMemoryKeyPassword` Gradle properties (required for a release version), and `signingInMemoryKeyId` if the key holds only a signing subkey, and nmcp uploads to the Central Portal with the token in `mavenCentralUsername` and `mavenCentralPassword`, publishing automatically once the portal validates it. Both nmcp plugins go in `jmediasoup`, because nmcp refuses projects with the same name.
- `compileJava` records the project version in the module descriptor, where `RemoteSdp` reads it.
- The tests load the webrtc-java natives, whose classifier the build picks from the OS and CPU. The test JVM opens `dev.onvoid.webrtc` to the library module, so that tests can create webrtc-java events whose constructors are package-private.
