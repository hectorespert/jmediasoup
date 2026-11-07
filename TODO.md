# TODO: jmediasoup roadmap

Goal: a Java client for [mediasoup](https://mediasoup.org) with the same features as [pymediasoup](https://github.com/Huddle01/pymediasoup), using [mediasoup-client](https://github.com/versatica/mediasoup-client) as the reference where pymediasoup falls short.

> **Living document.** Every phase, task and step can change as the implementation progresses: they can be added, split, reordered or removed. If the implementation shows that a step is wrong, fix it here instead of following it to the letter.

## How to read this document

- Each subtask has an ID (`3.2.4`) and should fit in a small commit or PR.
- `Origin:` names the reference code. Paths are from pymediasoup (commit `7e6b7ab`) unless stated otherwise.
- `⚠️ do not copy` marks a bug or a stub of the original that must not be reproduced.
- Each phase ends with its acceptance criteria.
- The [parity matrix](#parity-matrix) maps the pymediasoup API to the phase that covers it.

## Summary

| Part | Phase | Content |
|---|---|---|
| A: MVP | [0](#phase-0-project-and-decisions) | Project and decisions |
| A: MVP | [1](#phase-1-foundations) | Errors, events, models and JSON |
| A: MVP | [2](#phase-2-ortc) | ORTC logic and RTP capabilities |
| A: MVP | [3](#phase-3-sdp) | SDP parser and writer |
| A: MVP | [4](#phase-4-device-and-transport-with-fakehandler) | `Device` and `Transport` with `FakeHandler` |
| A: MVP | [5](#phase-5-webrtcjavahandler-receiving) | `WebRtcJavaHandler`: receiving |
| A: MVP | [6](#phase-6-webrtcjavahandler-sending) | `WebRtcJavaHandler`: sending |
| A: MVP | [7](#phase-7-mvp-milestone) | MVP milestone with a real server |
| B: parity | [8](#phase-8-media-control) | Media control |
| B: parity | [9](#phase-9-data-channels) | Data channels |
| B: parity | [10](#phase-10-stats-and-ice) | Stats and ICE |
| B: parity | [11](#phase-11-codecs-and-encodings) | Codecs, simulcast and SVC |
| B: parity | [12](#phase-12-media-utilities) | Media utilities |
| B: parity | [13](#phase-13-release) | Release |
| C: extras | [14](#phase-14-beyond-pymediasoup-optional) | Beyond pymediasoup |

**MVP definition:** against a real mediasoup server and with an automated test, `Device.load`, create send and recv transports, produce audio and video from a custom source (without simulcast), consume audio and video with a sink, and close cleanly while observing `connectionState`.

**What "parity" means:** the same public API, but working where pymediasoup does not. Several functions of the original are stubs because of aiortc limitations, and here they must really work.

**Deliberate exclusion:** Plan B. It exists in `media_section.py` and `remote_sdp.py`, but no handler uses it.

## Decisions

**Already made**

- Maven coordinates: `io.github.hectorespert:jmediasoup`, with the root package `io.github.hectorespert.jmediasoup`.
- Test server: the third-party image `mmahdisonic/mediasoup-demo`, pinned by digest (section 0.4). Locally it runs with podman.
- License: Apache-2.0. The code to port is MIT (pymediasoup, sdp-transform) and ISC (mediasoup-client, h264-profile-level-id), with their notices in `NOTICE`.
- Subproject: `jmediasoup`, which names the artifact without extra configuration.
- WebRTC stack: `dev.onvoid.webrtc:webrtc-java` (Apache-2.0), confirmed by the spike of section 0.3. Validated locally on Linux x86_64; it still has to be tried against a real mediasoup server and on other platforms.

**Design** (section 0.5)

- Asynchronous API with `CompletableFuture`. Whoever prefers a blocking style calls `.join()` on a virtual thread.
- Integration tests (section 7.1): an `integrationTest` Gradle suite with Testcontainers, on the classpath, as most applications use the library; the unit tests already cover the module path, and Testcontainers is not modular. `check` depends on it, so `build` and the CI run it. Without Docker, or outside Linux until `0.4.9` is done, the tests are skipped. Locally with podman, it is enough to enable its socket and point `DOCKER_HOST` at it. The tests talk to the demo through its protoo signaling (section 7.2), and the media that jmediasoup receives before it can send comes from an ffmpeg container (`mwader/static-ffmpeg`, pinned by digest) that publishes as a broadcaster (`5.2.9`).
- webrtc-java handler (section 5.1): `WebRtcJavaHandler` in the exported package `handler.webrtc`. The handler factory supports both forms of libmediasoupclient: `createFactory(peerConnectionFactory)` uses a `PeerConnectionFactory` of the application, which stays the application's, and `createFactory()` creates its own with `HeadlessAudioDeviceModule`, released when the factory is closed (`AutoCloseable`). The tracks to send come from that same `PeerConnectionFactory`. The webrtc-java callbacks complete their futures on a virtual thread, so nothing of jmediasoup runs on the signaling thread of the factory. The tests with natives go in the `test` task, with the classifier picked by OS and CPU.
- Transports (section 4.3): two types, `SendTransport` and `RecvTransport`, which extend a sealed class `Transport`, like libmediasoupclient and mediasoup-client-android, instead of the single `Transport` of pymediasoup. So `produce` only exists on the send one and `consume` on the receive one. They are created with the handlers as separate arguments: `createSendTransport(options, connect, produce)` and `createRecvTransport(options, connect)`. `producedata` will be added in Phase 9 with a four-parameter overload. `ConnectHandler` returns a `CompletionStage<?>` whose value is ignored, so that the signaling future can be returned directly. `appData` is read-only, as in pymediasoup (its setter raises an exception); a setter could be added without breaking anything. The events that `close()` causes (the `close` observer, `transportclose`) are delivered afterwards, on the transport executor, like the `newtransport` of the `Device`.
- Threading (section 4.3): each transport has a serial executor (`internal.event.SerialExecutor`) over shared virtual threads, where all the user code runs: listeners and handlers. None of it runs on a native thread or on the caller's, nor two at once, and it can block without stopping the factory; it does not inherit thread-locals (`MDC`). Operations (`produce`, `consume`...) also go through a queue like the `AwaitQueue` of mediasoup-client (`internal.event.OperationQueue`), which chains them one at a time and on close rejects the pending ones with `InvalidStateException`. Handler events do not wait for the queue: they are delivered as soon as they arrive, on the serial executor, as in mediasoup-client. Our own classes instead of Guava (`MoreExecutors.newSequentialExecutor`, `ExecutionSequencer`), which was removed in `0.1.3` and does not reject what is pending. `ReentrantLock` is used instead of `synchronized` so as not to pin virtual threads on Java 21–23.
- Events: `connect` and `produce` are passed when creating the transport and return a `CompletableFuture`, so they cannot be missing. The rest are typed listeners (`onConnectionStateChange(...)`) that return a subscription to unsubscribe.
- Listeners (section 1.2): `Consumer<T>` for events with a value and `Runnable` for those without. `onXxx` returns a `Subscription` (`AutoCloseable`, idempotent `close()`). As soon as `close()` returns, the listener receives nothing else, not even the event being delivered; one added during a delivery starts with the next event. If a listener throws an exception, it is logged at ERROR level with SLF4J and the others are still notified.
- Asynchronous handlers (section 1.2): `connect` and `produce` are our own interfaces (`ConnectHandler`, `ProduceHandler` with a request record) that return a `CompletionStage`. If the handler throws, returns `null` or its stage fails, the operation fails with `MediasoupClientException` and the original cause. jmediasoup sets no timeout; the user sets it in their signaling.
- Closed text types as enums with their JSON value.
- The public API uses the webrtc-java types (`MediaStreamTrack`, `RTCRtpSender`...), as pymediasoup uses the aiortc ones. Only the data channel has its own abstraction (`handler.DataChannel`, section 1.7); sender and receiver are exposed as `@Nullable` and are `null` with `FakeHandler`, which uses a test subclass of `MediaStreamTrack`. The webrtc-java jar is needed, but not its natives, except with `WebRtcJavaHandler`. It leaves Android out.
- Handler (section 4.1): the contract of mediasoup-client 3.26 in the `handler` package, with the names `Handler` and `HandlerFactory`. The factory gives the native capabilities by direction and creates ready handlers; the handler notifies the transport through a `HandlerListener` it receives in its options, and has every method of 3.26 from the start.
- A single artifact, `jmediasoup`, with the handler pluggable through an interface. `FakeHandler` only in tests, as in pymediasoup.
- Dependencies: webrtc-java, `jackson-annotations` and JSpecify as `api`; SLF4J as `implementation`; `jackson-databind` only in tests. The webrtc-java natives are declared by the user, with instructions for Gradle and Maven.
- Nullness with JSpecify: `@NullMarked` on each package and `@Nullable` on what is optional.
- Models as `record`s with Jackson annotations. jmediasoup needs no JSON at runtime: the user converts with their own `ObjectMapper`, from Jackson 2 or 3. Gson would need adapters (enums, `OS`/`MIS`, numbers inside a `Map`); our own `fromMap`/`toMap` can be added later without breaking anything.
- `appData` as an immutable `Map<String, Object>` that allows `null` values.
- Logging with SLF4J 2.
- Java 21 as the minimum version. It could go down to 17 without changing other decisions (webrtc-java is Java 8), but 21 is kept for pattern matching in `switch`, `SequencedMap` and virtual threads.
- Models (section 1.3) in exported subpackages by area: `jmediasoup.rtp`, `jmediasoup.sctp` and `jmediasoup.transport`. What is optional is `null` and is not filled in with the pydantic defaults; absent lists and maps are empty and immutable. The constructor validates what is required and the ranges and formats of each field (payload type, unsigned 32-bit SSRC, `mimeType`...); the rules across fields go in ORTC, except the contradictions within a single record that do not depend on the context (reliability of `SctpStreamParameters`). The records that the user builds with 4 or more optional fields have a hand-written builder.
- Options (section 1.6): `ProducerOptions`, `ConsumerOptions`, `DataProducerOptions`, `DataConsumerOptions` and `ProducerCodecOptions` go in the root package, next to the classes that use them. The ones that only the user builds and are not serialized have their flags as `boolean`, with the default of the original in the builder. `opusNack` and `streamId` are added from mediasoup-client; `onRtpSender` and `onRtpReceiver` are left for Phase 14.
- Exception to 0.5.5 (section 1.5): ICE servers are our own record, `IceServer`, because it is configuration that often arrives as JSON and the `RTCIceServer` of webrtc-java is mutable. The handler converts it.
- JSON (section 1.8): an unknown enum value fails on read, with a clear message, instead of being read as `null`.
- ORTC (section 2.3) and SDP (Phase 3): where pymediasoup and mediasoup-client differ, mediasoup-client is followed, as it has the current version of the algorithm. The API is still the one of pymediasoup.
- Capabilities (section 2.4): the model of mediasoup-client 3.26. The handler gives native capabilities by direction, `Device.load` computes extended receive capabilities and extended send capabilities (with the `preferLocalCodecsOrder` option), and `canSend` and `canReceive` work on the send and receive capabilities, not on the extended ones.
- Codec `parameters`: a `Map<String, Object>` that keeps the order and the JSON type of each value (text or number). ORTC compares them with `internal.ParameterValues`, which treats `100`, `100L` and `"100"` as equal. `RtpCodec` is a sealed interface implemented by `RtpCodecCapability` and `RtpCodecParameters`.
- Exceptions (section 1.1): a checked base, `MediasoupClientException extends Exception`, with `UnsupportedException` and `InvalidStateException` as subclasses. Synchronous methods declare it; in a `CompletableFuture` it arrives as the cause of the failure (`CompletionException` in `join()`). An invalid argument throws `IllegalArgumentException`, and a required one that is `null`, `NullPointerException`. The generic `Exception`s of the original (unexpected SDP or transceiver) throw the base. A codec chosen in `ProducerOptions.codec` that cannot be sent throws `UnsupportedException` (section 2.5), although the original throws `TypeError`.
- `module-info.java` from the start, exporting only the public packages. Internal packages go under `io.github.hectorespert.jmediasoup.internal` (`.internal.sdp`, `.internal.sdptransform`, `.internal.h264`...) and are not exported.
- Tests run inside the library module (whitebox, with `--patch-module`) through the `org.gradlex.java-module-testing` plugin. So they see the internal packages and check the `requires` and `exports` at the same time.
- Our own ports of `sdp-transform` and `h264-profile-level-id` as internal packages (`internal.sdptransform` and `internal.h264`). The SDP code of mediasoup-client (`handlers/sdp`) goes in `internal.sdp`. There is no usable Java alternative (see `0.5.13`). The parsed SDP is represented with `Map<String, Object>`, like the sdp-transform object and the `dict` of pymediasoup.
- Versions: SemVer. `0.1.0` with the MVP (`7.4`), a minor version when each parity phase is closed (`0.2.0` Phase 8, `0.3.0` Phase 9, `0.4.0` Phase 10, `0.5.0` Phase 11 and `0.6.0` Phase 12) and `1.0.0` with Phase 13. Fixes between phases come out as patches. In `0.x` a minor version can break the API; from `1.0.0` on, strict SemVer. Releases are only published from CI, when a GitHub release is published; its tag is the version, `X.Y.Z` without prefix, as in the other Java libraries of the author.
- The version is computed from git by the axion-release plugin (Apache-2.0), applied in the root project. `gradle-git-versioning` (qoomon) was planned first, because its version 7.0 supports the configuration cache, but it fails the build in shallow clones, which is how GitHub Actions checks out, even when there are no tags. axion-release fetches the full history by itself on CI (`CI=true`), and in `7.4.4` it reused the configuration cache and recomputed the version on each new tag or commit, despite its open issues about the cache.
- Server: mediasoup v3. The CI validates the version of the image (3.19.14), and a recent one is added in Phase 13.

---

# Part A: up to the MVP

## Phase 0: project and decisions

### 0.1 Template cleanup

- [x] `0.1.1` Delete `org.example.Library` and `org.example.LibraryTest`.
- [x] `0.1.2` Remove `commons-math3` and `guava` from `lib/build.gradle.kts`.
- [x] `0.1.3` Remove `commons-math3` and `guava` from `gradle/libs.versions.toml`.
- [x] `0.1.4` Remove the comments generated by `gradle init` from the build scripts.
- [x] `0.1.5` Create the `io.github.hectorespert.jmediasoup` package with `package-info.java`.
- [x] `0.1.6` Add a smoke test so that `./gradlew test` runs something.
- [x] `0.1.7` Update "Project state" and the example commands in `CLAUDE.md`.

### 0.2 Coordinates, repository and license

- [x] `0.2.1` Set `group = "io.github.hectorespert"` in the build.
- [x] `0.2.2` Set the initial version `0.1.0-SNAPSHOT`.
- [x] `0.2.3` Decide between renaming the `lib` subproject or setting the `artifactId` in the publication: it is renamed to `jmediasoup`.
- [x] `0.2.4` Apply `maven-publish` and check with `publishToMavenLocal` that the artifact comes out as `jmediasoup`.
- [x] `0.2.5` Create the remote repository on GitHub (there was no remote configured).
- [x] `0.2.6` Verify the `io.github.hectorespert` namespace in Central Portal.
- [x] `0.2.7` Choose the project license and add `LICENSE`.
- [x] `0.2.8` Review the licenses of the code to port (pymediasoup, mediasoup-client, sdp-transform, h264-profile-level-id) and keep their copyright notices in a `NOTICE`.

### 0.3 webrtc-java spike

The goal was to confirm or discard the stack, not to leave final code.

- [x] `0.3.1` Add `webrtc-java` 0.20.0 to the version catalog.
- [x] `0.3.2` Resolve the Linux x86_64 native artifact.
- [x] `0.3.3` Select the native classifier by OS in Gradle, compatibly with the configuration cache.
- [x] `0.3.4` Create and destroy a `PeerConnectionFactory` and an `RTCPeerConnection`, without leaks or hangs on close.
- [x] `0.3.5` Check that it works without audio devices or a display (CI environment).
- [x] `0.3.6` Generate an offer with audio and video transceivers, and save the SDP as a fixture.
- [x] `0.3.7` Review the codecs, `extmap`, `rtcp-fb` and the presence of `a=ssrc` lines in that SDP.
- [x] `0.3.8` Connect a `CustomAudioSource` to an `AudioTrackSink` through two local peer connections.
- [x] `0.3.9` Connect a `CustomVideoSource` to a `VideoTrackSink` through two local peer connections.
- [x] `0.3.10` Check `setEnabled`, the track state and how its end (`ended`) is detected.
- [x] `0.3.11` Create a `negotiated` data channel with a fixed `id` and exchange messages.
- [x] `0.3.12` Check `sendEncodings` with several `rid` and the resulting `a=rid` and `a=simulcast` lines.
- [x] `0.3.13` Check `RTCOfferOptions.iceRestart` and `setConfiguration`.
- [x] `0.3.14` Check `getStats` at the peer connection, sender and receiver levels.
- [x] `0.3.15` Find out on which threads the native callbacks arrive and whether they can block.
- [x] `0.3.16` Check whether tracks can be instantiated or faked without loading the natives (it shapes `FakeHandler`).
- [x] `0.3.17` Find out whether the native capabilities depend on the added tracks (in aiortc they have to be passed to `createFactory`).
- [x] `0.3.18` List the supported platforms and architectures.
- [x] `0.3.19` Record the result and confirm or discard the stack: **webrtc-java confirmed**. The spike code has been deleted.

**Spike result** (webrtc-java 0.20.0, Linux x86_64, Java 21, without `DISPLAY` or an audio server)

It works between two local peer connections. It still has to be tried against a real mediasoup server (Phase 7) and on macOS and Windows.

| Task | Result |
|---|---|
| `0.3.2`, `0.3.3` | In Gradle the native classifier has to be declared: without it, it fails with `UnsatisfiedLinkError`. Selecting it by OS and CPU reuses the configuration cache. |
| `0.3.4` | 1000 peer connections in one factory: the RSS does not grow. 300 factory and peer connection cycles: about 16 kB per cycle, without hangs. |
| `0.3.5` | `HeadlessAudioDeviceModule` is required: `new PeerConnectionFactory()` fails if there is no audio system. |
| `0.3.6`, `0.3.7` | Offer with Opus, VP8, VP9, H264 and AV1, RTX, `sdes:mid`, `rtp-stream-id`, `transport-wide-cc`, `a=ssrc` and `ssrc-group:FID`. SDP in `jmediasoup/src/test/resources/sdp/webrtc-java-0.20.0/`. |
| `0.3.8`, `0.3.9` | Custom audio and video reach the sink. Audio is received as 16-bit, 48 kHz mono PCM. |
| `0.3.10` | `setEnabled(false)` silences the receiver. `MediaStreamTrack` has no `stop()`. `transceiver.stop()` and renegotiating ends the remote track and fires `ended`; the local one stays `LIVE`, also after `source.dispose()`. |
| `0.3.11` | `negotiated` channel with a fixed `id`, in text and binary. A reliable channel returns `getMaxRetransmits() == 0`, not `-1`. |
| `0.3.12` | Correct `a=rid` and `a=simulcast:send r0;r1;r2`, without `a=ssrc`. `scalabilityMode` is kept. It has not been checked that the three layers are sent. |
| `0.3.13` | `iceRestart` changes the `ufrag` and reconnects. `setConfiguration` is applied. |
| `0.3.14` | `getStats` works at the three levels, with `bytesSent` and `bytesReceived`. |
| `0.3.15` | Signaling, ICE, connection, data channel and stats callbacks on a single thread per factory; the sinks, on another one. **Blocking in a callback stops the whole factory**, including the synchronous getters, and another asynchronous operation awaited inside the callback does not finish until it is released. Another factory is not affected. |
| `0.3.16` | `MediaStreamTrack` can be subclassed without natives, but `AudioTrack`, `VideoTrack`, `RTCRtpSender`, `RTCRtpReceiver`, `RTCRtpTransceiver`, `RTCDataChannel` and `RTCPeerConnection` have a private constructor. The data classes are used without natives. |
| `0.3.17` | No tracks are needed: an offer with transceivers without a track gives the same extensions, and as the only change in codecs it adds `flexfec-03`. |
| `0.3.18` | Linux (x86_64, aarch64, aarch32), macOS (x86_64, aarch64) and Windows (x86_64, aarch64). On Linux it needs glibc (Alpine does not work) and the X11, `libgbm` and `libdrm` libraries. 10 MB native jar. |

**Consequences for the design**

- `0.5.2`: no blocking work in native callbacks, the user's included. It is mandatory, not a preference.
- `0.5.5`: our own abstractions are needed to test `FakeHandler` without natives.
- `0.5.7`: it has to be decided how users of the library get the natives from Gradle.
- `8.2.9`: `trackended` on local tracks will have to be emitted by jmediasoup, because the library does not.

### 0.4 mediasoup Docker image

Chosen image: `mmahdisonic/mediasoup-demo`, pinned by digest. No official image was found, and the community ones have hardly any documentation, so they were inspected before using them.

- [x] `0.4.1` List candidate images (Docker Hub, GHCR and GitHub projects that publish an image).
- [x] `0.4.2` Check that they ship a known, recent mediasoup v3.
- [x] `0.4.3` Check that the `Dockerfile` or the source code can be inspected.
- [x] `0.4.4` Check that they have documented signaling that allows creating transports, producing and consuming.
- [x] `0.4.5` Check that `announcedAddress` and the RTC port range are configurable.
- [x] `0.4.6` Check that the license is compatible with its use in CI.
- [x] `0.4.7` Choose the image and pin it by digest, not by tag.
- [x] `0.4.8` Validate UDP networking on Linux with `--network host`.
- [ ] `0.4.9` Validate UDP networking on macOS and Windows with a port range and `announcedAddress`.
- [x] `0.4.10` If no candidate meets the criteria, revisit the decision before starting Phase 7: not needed, the candidate meets them.

**Result** (with podman, which replaces Docker locally)

Chosen image: `docker.io/mmahdisonic/mediasoup-demo@sha256:2e60f70cf77ed9ac8b3ef2adfcdb9df6666412c6c543831d5d97bdc37609c8c2`.

| Image | mediasoup | Code and license | Signaling | Conclusion |
|---|---|---|---|---|
| `mmahdisonic/mediasoup-demo` | 3.19.14 (December 2025) | Official demo, ISC | protoo over WebSocket, like the pymediasoup example | Chosen |
| `mirotalk/sfu` | 3.32.2 | Public, AGPL-3.0 | socket.io, the application's own protocol | Alternative if a recent version is needed |
| `achrafyoussef/bizcord-mediasoup` | 3.19.22 | No repository or license | REST with a shared secret | Discarded |
| `shubhamwrktalk/mediasoup-demo` | 3.18.0 | No Dockerfile or documentation | Undocumented | Discarded |
| `iabsis/mediasoup-api` | 3.19.0 | Repository not found | Undocumented | Discarded |
| `gbplkr/mediasoup-demo` | 3.15.7 | No Dockerfile or documentation | Undocumented | Discarded |
| `vanjoge/mediasoup-demo:v3` | 3.8.3 | Old | Undocumented | Discarded |

**Provenance:** `src/` matches `versatica/mediasoup-demo@e6bbe34` except for an added `isValidPort` function, the mediasoup `node/lib` is identical to the npm package, and the worker has the same SHA-256 as the one of the official 3.19.14 release. `lib/` (the compiled JavaScript) has only been reviewed looking for network or process calls.

**Test with rootless podman 5.7, with `--cap-drop=all`:** over protoo, `getRouterRtpCapabilities`, `join` and `createWebRtcTransport` (with SCTP) work, and the server answers a signed STUN over UDP on both transports. It works with `--network host` and with the ports published on `127.0.0.1`. DTLS and media have not been tried: that comes with the E2E of Phase 7.

**What shapes the signaling client of Phase 7:**

- protoo has to be used. The REST API only creates a `PlainTransport`, and it does not create rooms either: the room is created by the first WebSocket connection.
- Both the WebSocket and the API require `Origin: http://localhost:4443`, even if published on another port.
- `createWebRtcTransport` takes the direction in `appData.direction`, with the values `producer` and `consumer`.
- `MEDIASOUP_ANNOUNCED_ADDRESS` is applied. `MEDIASOUP_MIN_PORT` and `MEDIASOUP_MAX_PORT` do not affect the WebRTC transports, which use a `WebRtcServer` with a fixed port per worker (44443 in the test). One worker is started per CPU, so when publishing ports that range has to be covered.
- It serves HTTP without TLS.

**Risks:** it is published by a personal account that could delete it (it would have to be republished or built from the official demo), and it is 13 minor versions behind mediasoup, so the tests only validate that version of the server (see `0.5.14`).

**The public demo is not an option:** `v3demo.mediasoup.org` rejects connections without its `Origin`, and faking it to use someone else's server is not acceptable. The automated tests of pymediasoup do not use it either: they only use `FakeHandler`.

### 0.5 Design decisions

Each one is confirmed and recorded in the [Decisions](#decisions) section of this document.

- [x] `0.5.1` Async model: `CompletableFuture`. Rejected: a blocking API (risk of deadlock with the transport queue), a dual API and reactive libraries.
- [x] `0.5.2` Threading model: a serialized queue per transport, which also delivers the events. An injectable `Executor` for the listeners can be added later without breaking anything.
- [x] `0.5.3` Events: `connect` and `produce` required when creating the transport, and the rest as typed listeners. Rejected: an emitter by event name and a `Listener` interface per class.
- [x] `0.5.4` Closed text types (`MediaKind`, `DtlsRole`, `ConnectionState`...): enums with their JSON value.
- [x] `0.5.5` webrtc-java types in the public API, and own abstractions only inside the handler (sender, receiver, data channel). Rejected: own types with `unwrap()`.
- [x] `0.5.6` Modules: a single `jmediasoup` artifact and a handler pluggable through an interface.
- [x] `0.5.7` Scope of dependencies and natives: see [Decisions](#decisions). Gradle variants that pick the natives by themselves are left for Phase 13.
- [x] `0.5.8` Nullness: JSpecify.
- [x] `0.5.9` Type of `appData`: an immutable `Map<String, Object>`.
- [x] `0.5.10` Logging: SLF4J 2. Rejected: `System.Logger`.
- [x] `0.5.11` `module-info.java` is published from the start. All dependencies are already named modules (`webrtc.java`, `org.slf4j`, `com.fasterxml.jackson.annotation`, `org.jspecify`).
- [x] `0.5.12` Representation of the parsed SDP: `Map<String, Object>`, like `sdp-transform`. It is internal, so it can be typed later.
- [x] `0.5.13` Review alternatives to the ports: `libSDPtransform-android` only contains a binary `.aar`, without sources or license. `crow-misia/sdp` (Kotlin, without a module name and with a typed model), `medooze/sdp` (archived), the SIP-era SDP libraries and a Java port of an unlicensed demo are not usable either. There is no Java library for `h264-profile-level-id`.
- [x] `0.5.14` Supported server versions: mediasoup v3.
- [x] `0.5.15` Confirm the models as `record`s with Jackson annotations, without `jackson-databind` at runtime.
- [x] `0.5.16` Confirm our own ports of `sdp-transform` and `h264-profile-level-id`, as internal packages.

### 0.6 CI and quality

- [x] `0.6.1` GitHub Actions workflow: `./gradlew build` on push to `main` and on PRs (`.github/workflows/build.yml`). Actions are pinned by SHA. The workflow is created from the GitHub web UI, because the `gh` token of the sessions lacks the `workflow` scope.
- [x] `0.6.2` Gradle cache with `setup-gradle` and `cache-provider: basic` (the GitHub Actions cache, instead of the commercial one it uses by default). Only `main` builds write to it.
- [x] `0.6.3` Publish the test reports as a workflow artifact (`test-reports`, 14 days).
- [x] `0.6.4` Formatter: Spotless, part of `./gradlew build`. On Java it applies palantir-java-format, removes unused imports and adds the SPDX license header (except in `package-info.java` and `module-info.java`). On the `.gradle.kts` scripts it applies ktlint. On Markdown, YAML, TOML and Git files it trims trailing whitespace and ensures the final newline.
- [x] `0.6.5` Enable automatic dependency updates. Done with Dependabot (`.github/dependabot.yml`): once a month it checks the catalog, the Gradle plugins and the Actions SHAs, opens one PR per ecosystem and only proposes versions released at least 14 days earlier (`cooldown`). It also updates the Gradle wrapper; its commit leaves `gradlew.bat` with CRLF in the index, against `.gitattributes`, which `git add --renormalize gradlew.bat` fixes. The Actions PRs touch `.github/workflows/`, so they are merged from the web UI.

### 0.7 Apply the decisions to the build

- [x] `0.7.1` `module-info.java` of the `io.github.hectorespert.jmediasoup` module, with `requires transitive org.jspecify`. At first it exported nothing: javac does not allow exporting a package that only has `package-info.java`, so the `exports` of the root package came with its first public type (`1.1.1`).
- [x] `0.7.2` Check that the tests run on Gradle's module path. By default Gradle runs them on the classpath; with `org.gradlex.java-module-testing` 1.8.1 they run inside the module, and `SmokeTest.testsRunInTheLibraryModule` checks it.
- [x] `0.7.3` Set the name of the internal packages, which `module-info.java` does not export: `io.github.hectorespert.jmediasoup.internal.*`.
- [x] `0.7.4` Add JSpecify as `api` and `@NullMarked` in `package-info.java` (JSpecify 1.0.1).

**Phase 0 acceptance**

- `./gradlew build` passes without leftovers of the template, locally and in CI.
- `publishToMavenLocal` generates `io.github.hectorespert:jmediasoup`.
- The WebRTC stack and the Docker image are confirmed, or the decision has been revisited.
- The design decisions of section 0.5 are recorded, and the JPMS module compiles and passes the tests.

## Phase 1: foundations

### 1.1 Errors

Origin: `pymediasoup/errors.py`

- [x] `1.1.1` Base exception of the project (the equivalent of `Error`): `MediasoupClientException`, checked. It is the first public type, so it adds `exports io.github.hectorespert.jmediasoup` to `module-info.java`.
- [x] `1.1.2` `UnsupportedError`: `UnsupportedException`.
- [x] `1.1.3` `InvalidStateError`: `InvalidStateException`.
- [x] `1.1.4` Decide which Java exception replaces each `TypeError` of the original: `IllegalArgumentException`, or `NullPointerException` if a required argument is missing. The `TypeError`s for a missing `connect` or `produce` listener disappear, because they are passed when creating the transport.

### 1.2 Events and observers

Origin: `pymediasoup/emitter.py`, `pyee`

- [x] `1.2.1` Concurrency-safe collection of listeners, with add and remove: `internal.event.Listeners<T>` and `RunnableListeners`, on `CopyOnWriteArrayList`.
- [x] `1.2.2` Isolate errors: the exception of a listener does not stop the others from being notified and is logged with SLF4J, added here as `implementation` (2.0.20; `slf4j-simple` only in tests).
- [x] `1.2.3` Contract for listeners that return an asynchronous result (replaces `emit_for_results`): `internal.event.AsyncHandlers`, with `run` for those that return no value and `call` for those that do. The `ConnectHandler` and `ProduceHandler` interfaces are created with their types in `4.3.5` and `4.3.7`.
- [x] `1.2.4` A clear error if a required listener fails or returns a null result: `MediasoupClientException` with a different message for each case and the original cause. They cannot be missing, because they are passed when creating the transport (`0.5.3`). ⚠️ do not copy: `ids[0]` fails with `IndexError` if the `produce` listener fails or returns nothing.
- [x] `1.2.5` Tests of delivery order, unsubscribing during delivery and error propagation, including a concurrency test.
- [x] `1.2.6` A subscription returned when registering a listener, to unsubscribe: `Subscription`, in the root package.

### 1.3 RTP parameters

Origin: `pymediasoup/rtp_parameters.py`

- [x] `1.3.1` `MediaKind`, an enum with `value()` and `fromValue()`.
- [x] `1.3.2` `RtcpFeedback`.
- [x] `1.3.3` `RtpCodecCapability`.
- [x] `1.3.4` `RtpCodecParameters`.
- [x] `1.3.5` A common codec type so that `matchCodecs` accepts capabilities and parameters (`Codec` and `RtpCodec` in the original): the sealed interface `RtpCodec`.
- [x] `1.3.6` `RtpHeaderExtension` and its `direction` (`RtpHeaderExtensionDirection`).
- [x] `1.3.7` `RtpHeaderExtensionParameters`.
- [x] `1.3.8` `RtpCapabilities`, with `fecMechanisms` as in pymediasoup.
- [x] `1.3.9` `RTX` (retransmission SSRC): `Rtx`, with the SSRC as a `long`.
- [x] `1.3.10` `RtpEncodingParameters`, with all its fields optional, a builder and `toBuilder()`. It adds `active` from mediasoup-client, and `scaleResolutionDownBy` and `maxFramerate` are `Double` (⚠️ they are `int` in pymediasoup). The priority is the `Priority` enum.
- [x] `1.3.11` `RtcpParameters`.
- [x] `1.3.12` `RtpParameters`, with `msid` from mediasoup-client.
- [x] `1.3.13` `ExtendedCodec`, public because the handler receives it (`0.5.6`).
- [x] `1.3.14` `ExtendedHeaderExtension`.
- [x] `1.3.15` `ExtendedRtpCapabilities`.
- [x] `1.3.16` Represent the codec `parameters` with types that stay stable when comparing (an `apt` can arrive as a number or as text): a map with the JSON types, compared with `internal.ParameterValues`.
- [x] `1.3.17` Validate the required fields in the constructor (pydantic does it in the original), and also ranges and formats, with `internal.Checks`.
- [x] `1.3.18` Defensive copies of lists and maps (replaces `copy(deep=True)`). Maps keep the insertion order.

### 1.4 SCTP parameters

Origin: `pymediasoup/sctp_parameters.py`

- [x] `1.4.1` `NumSctpStreams`, with the fields `os()` and `mis()` (`OS` and `MIS` in JSON, `1.8.4`).
- [x] `1.4.2` `SctpCapabilities`.
- [x] `1.4.3` `SctpParameters`. Only `port` is required, to accept the fields of mediasoup 3.19 (`OS`, `MIS`, `maxMessageSize`) and those of newer servers (`maxSendMessageSize`, `maxReceiveMessageSize`).
- [x] `1.4.4` `SctpStreamParameters`. `ordered` stays `null` if absent, like the rest of the optionals; inferring it is the job of `9.3.3`.
- [x] `1.4.5` Validate the combination of `ordered`, `maxPacketLifeTime` and `maxRetransmits` described in the original. Contradictions (both maximums, or `ordered: true` with one of them) fail in the constructor, because they do not depend on the context.

### 1.5 Transport parameters

Origin: `pymediasoup/models/transport.py`

- [x] `1.5.1` `IceParameters`, including `iceLite`.
- [x] `1.5.2` `IceCandidate`, with enums for `protocol` (`TransportProtocol`), `type` and `tcpType`. It keeps `address` and the deprecated `ip`, with at least one required, and `effectiveAddress()` reads whichever is present. The priority is a `long` (unsigned 32 bits).
- [x] `1.5.3` `DtlsFingerprint`, with the `FingerprintAlgorithm` enum.
- [x] `1.5.4` `DtlsRole`.
- [x] `1.5.5` `DtlsParameters`. `role` stays `null` if absent, which means `auto`.
- [x] `1.5.6` `ConnectionState`.
- [x] `1.5.7` `IpVersion`, with a numeric value.
- [x] `1.5.8` `PlainRtpParameters`.
- [x] `1.5.9` `TransportOptions`, with a builder and `toBuilder()` to add the local settings to what the server returns. Without `additionalSettings` or `proprietaryConstraints`, which are decided in `10.2.8`.
- [x] `1.5.10` Internal transport options (`InternalTransportOptions`): moved to `4.3.1`, because they need the `HandlerFactory` of `4.1.7`.
- [x] `1.5.11` `TransportDirection` enum (`send`, `recv`).
- [x] `1.5.12` Own model of `RTCIceServer`, if `0.5.5` requires it: the record `IceServer` (`urls`, `username`, `credential`, as in W3C), with the credential hidden in `toString()`, and the `IceTransportPolicy` enum.

### 1.6 Producer and consumer options

Origin: `producer.py`, `consumer.py`, `data_producer.py`, `data_consumer.py`

- [x] `1.6.1` `ProducerCodecOptions`, with its nine fields, `opusNack` from mediasoup-client and a builder.
- [x] `1.6.2` `ProducerOptions`, with the defaults of the original (`stopTracks = true`, `disableTrackOnPause = true`, `zeroRtpOnPause = false`) as `boolean`s of the builder. The track is required, and it adds `streamId` from mediasoup-client.
- [x] `1.6.3` `ConsumerOptions`, with `streamId` from mediasoup-client.
- [x] `1.6.4` `DataProducerOptions`, with a builder and the same reliability rules as `SctpStreamParameters`.
- [x] `1.6.5` `DataConsumerOptions`.

### 1.7 Media abstractions

Origin: `pymediasoup/types/webrtc.py` and the aiortc types that the original uses

The public API uses the webrtc-java types (`0.5.5`). Sender and receiver need no abstraction, because Producer and Consumer only expose them and operate through the handler with the `localId`. The data channel does, because DataProducer and DataConsumer operate on it and `RTCDataChannel` cannot be created without natives.

- [x] `1.7.1` Test subclass of webrtc-java's `MediaStreamTrack`, which can be instantiated without natives (per `0.3.16`): `FakeMediaStreamTrack`, in the `fake` package of the tests.
- [x] `1.7.2` ~~Internal sender interface~~. Discarded: the handler result carries a `@Nullable RTCRtpSender`, `null` with `FakeHandler`, as in pymediasoup.
- [x] `1.7.3` ~~Internal receiver interface~~. Discarded: the handler result carries a `@Nullable RTCRtpReceiver`.
- [x] `1.7.4` Data channel interface (`label`, `protocol`, `readyState`, `send`, `close`, buffer and events): `handler.DataChannel`, public because the handler is pluggable (`0.5.6`), with an `Observer` for its events. Messages are `DataChannelMessage`, in the root package, which copies the bytes.
- [x] `1.7.5` Fake implementations for the tests: `fake.FakeDataChannel`, which the test opens, feeds and observes.

### 1.8 JSON

The user converts the models with their own `ObjectMapper` (`0.5.15`), so everything is configured with annotations.

- [x] `1.8.1` Add `jackson-annotations` as `api` (2.22, the one Jackson 2.22 and 3.2 use), and `jackson-databind` 2 (2.22.4) and 3 (3.2.4) only for tests.
- [x] `1.8.2` Annotate the models to omit nulls and ignore unknown fields (`@JsonInclude(NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown = true)` on each JSON record; not on `ProducerOptions`, which carries a track). Required primitive components carry `@JsonProperty(required = true)`, so that their absence fails instead of giving 0.
- [x] `1.8.3` Serialize enums with the value mediasoup expects: `@JsonValue` on `value()` and `@JsonCreator` on `fromValue()`. An unknown value fails with the message of `fromValue`.
- [x] `1.8.4` Names that do not follow camelCase, such as `OS` and `MIS` of `NumSctpStreams` and `SctpParameters`, with `@JsonProperty`.
- [x] `1.8.5` Check that the round trip keeps the types of the codec `parameters`, and their order.
- [x] `1.8.6` Round-trip tests with Jackson 2 and with Jackson 3: `json.JsonRoundTripTest` runs each case with both (`@WithEachJackson`). Also checked from a consuming JPMS module, without `opens`.
- [x] `1.8.7` `IceServer.urls`: also accept a single string, which the W3C standard allows (`"urls": "turn:..."`), with `ACCEPT_SINGLE_VALUE_AS_ARRAY`.

### 1.9 Fixtures

Origin: `tests/fake_parameters.py`, `tests/fake_handler.py`

- [x] `1.9.1` Port `generateRouterRtpCapabilities`: `fixtures/router-rtp-capabilities.json` and `fake.FakeParameters.routerRtpCapabilities()`. The JSON files were generated from the code of the original with `ast`, not copied by hand.
- [x] `1.9.2` Port the native RTP capabilities of `FakeHandler`: `fixtures/native-rtp-capabilities.json`.
- [x] `1.9.3` Port `generateTransportRemoteParameters`, as `TransportOptions` with a new id on each call. Without the `family` field of the candidates, which mediasoup v3 no longer sends.
- [x] `1.9.4` Port `generateConsumerRemoteParameters`, per codec (`audio/opus`, `audio/ISAC`, `video/VP8`). ⚠️ do not copy: in ISAC the first header extension uses the variable `id` as key instead of `"id"`.
- [x] `1.9.5` Port `generateDataProducerRemoteParameters` and `generateDataConsumerRemoteParameters`. ⚠️ do not copy: pydantic leaves `ordered = True` together with `maxPacketLifeTime`, which is contradictory; here `ordered` is absent.
- [x] `1.9.6` JSON round trip of all the fixtures: `json.FixturesRoundTripTest` walks `fixtures/` with Jackson 2 and 3, and checks that the object is preserved and that every input value is still in the output.

**Phase 1 acceptance** (met)

- Every fixture is read, validated and written back without losing data (`1.9.6`).
- Invalid inputs fail when the object is built, with a useful message (tests of `Checks` and of each model, and `JsonRoundTripTest`).

## Phase 2: ORTC

### 2.1 Port of h264-profile-level-id

Origin: the `h264-profile-level-id` package. It goes in an internal package (`0.5.16`). pymediasoup only uses `isSameProfile` and `generateProfileLevelIdForAnswer`, but it is ported whole from `versatica/h264-profile-level-id` 2.3.3, the original in JS that mediasoup-client uses, so that all its tests can be ported. It lives in `internal.h264`.

- [x] `2.1.1` Profile and level constants: the enums `Profile` and `Level` (with its `level_idc`), the record `ProfileLevelId` and the tables of profile patterns and level limits.
- [x] `2.1.2` Parse a `profile-level-id` string: `parseProfileLevelId`, which only accepts 6 hexadecimal digits (the original, with `parseInt`, accepts a sign and trailing garbage).
- [x] `2.1.3` Convert a profile and level to a string: `profileLevelIdToString`, and also `profileToString` and `levelToString`.
- [x] `2.1.4` Read the parameter from the `fmtp`, with its default value if missing: `parseSdpProfileLevelId`, which accepts the value as text or as a number because the `parameters` keep the JSON type.
- [x] `2.1.5` `isSameProfile`, and also `isSameProfileAndLevel`.
- [x] `2.1.6` `generateProfileLevelIdForAnswer` (`generateProfileLevelIdStringForAnswer` in the original), with `IllegalArgumentException` where the original throws `TypeError`, and `level-asymmetry-allowed` as `true`, `1` or `"1"`. Also `supportedLevel`.
- [x] `2.1.7` Port of the test cases of the original library: `H264ProfileLevelIdTest`, all but the one with an unknown profile, which a Java enum cannot represent, and with our own cases for numeric and signed values.

### 2.2 Scalability modes

Origin: `pymediasoup/scalability_modes.py`

- [x] `2.2.1` `ScalabilityMode` (spatial and temporal layers): a record in `internal.ortc`. It is internal although mediasoup-client exports `parseScalabilityMode`; only the handler uses it.
- [x] `2.2.2` `parse`, which only looks at the prefix (it accepts `_KEY` and other suffixes) and returns one layer of each if the mode is missing or invalid. ⚠️ do not copy: the regex uses `\\d` in a raw string, so the original always returns 1 and 1.
- [x] `2.2.3` Tests of `L1T3`, `S3T3`, `L3T3_KEY`, empty and null strings, and invalid values, plus the cases of `test.ts` of mediasoup-client.

### 2.3 Codec comparison

Origin: `pymediasoup/ortc.py`

- [x] `2.3.1` The constants `RTP_PROBATOR_MID`, `RTP_PROBATOR_SSRC` and `RTP_PROBATOR_CODEC_PAYLOAD_TYPE`, in `internal.ortc.Ortc`.
- [x] `2.3.2` `isRtxCodec`, case-insensitive, like mediasoup-client.
- [x] `2.3.3` `matchCodecs`: `mimeType` (case-insensitive), `clockRate` and `channels`. An audio codec without `channels` counts as 1, and in video they are ignored, as mediasoup-client does when normalizing.
- [x] `2.3.4` `matchCodecs`: H264, `packetization-mode`, which as in mediasoup-client is only compared in strict mode (pymediasoup always compares it).
- [x] `2.3.5` `matchCodecs`: strict H264, `isSameProfile`.
- [x] `2.3.6` `matchCodecs`: H264 with `modify`, using `generateProfileLevelIdForAnswer`. ⚠️ do not copy: the original mutates the input codec. Here `matchCodecs` returns a `CodecMatch` (or `null` if they do not match) with the negotiated parameters of both codecs, like mediasoup-client when it modifies both.
- [x] `2.3.7` `matchCodecs`: strict VP9, `profile-id`, with 0 by default and comparing numbers and text.
- [x] `2.3.8` `reduceRtcpFeedback`, with an empty `parameter` equivalent to an absent one.
- [x] `2.3.9` `matchHeaderExtensions`, which as in mediasoup-client only compares `kind` if both have it.

### 2.4 Extended capabilities

It follows the model of mediasoup-client 3.26: separate extended capabilities for sending and for receiving, each one with the native capabilities of that direction.

- [x] `2.4.1` `getExtendedRtpCapabilities`: codecs in strict mode, in the order of the router or, with `preferLocalCodecsOrder`, the local one. Each side carries its negotiated parameters (the H264 `profile-level-id`). A matched codec without `preferredPayloadType` throws `IllegalArgumentException`.
- [x] `2.4.2` `getExtendedRtpCapabilities`: match RTX by `apt`, comparing its numeric value, only if both sides have it.
- [x] `2.4.3` `getExtendedRtpCapabilities`: header extensions, in the order of the router.
- [x] `2.4.4` `getExtendedRtpCapabilities`: invert `direction` relative to the router; without a direction it is `sendrecv`.
- [x] `2.4.5` `getRecvRtpCapabilities` and `getSendRtpCapabilities`: codecs and RTX, with the payload types of the router or the local ones.
- [x] `2.4.6` `getRecvRtpCapabilities` and `getSendRtpCapabilities`: filter out the extensions that are not valid in their direction.
- [x] `2.4.7` `canSend`, on the send capabilities.
- [x] `2.4.8` `canReceive`, on the receive capabilities.

Difference with mediasoup-client: there `matchCodecs` modifies the router capabilities when negotiating H264, and the receive computation in `load` changes the `profile-level-id` that the send one sees afterwards. Here the models are immutable and each computation starts from the original capabilities.

### 2.5 Sending parameters

- [x] `2.5.1` `getSendingRtpParameters`: codecs and RTX, with the local payload types and parameters. Empty `mid` and encodings, and empty RTCP as in mediasoup-client.
- [x] `2.5.2` `getSendingRtpParameters`: the header extensions that can be sent, of the requested kind or without a kind. ⚠️ do not copy: pymediasoup drops those without a kind.
- [x] `2.5.3` `getSendingRemoteRtpParameters`: use the remote parameters of the codec.
- [x] `2.5.4` `getSendingRemoteRtpParameters`: reduce the feedback (Transport-CC, else REMB, else none).
- [x] `2.5.5` `reduceCodecs` without a given codec: the first one and its RTX.
- [x] `2.5.6` `reduceCodecs` with a given codec, compared in strict mode like mediasoup-client. ⚠️ do not copy: the `break` only runs if there is RTX, so without RTX it keeps adding matches.
- [x] `2.5.7` `reduceCodecs`: `UnsupportedException` if there is no match or the list is empty.
- [x] `2.5.8` `generateProbatorRtpParameters`. Without video codecs it throws `IllegalArgumentException`.

**Phase 2 acceptance** (met)

- Every function of `ortc.py` has tests, including H264, VP9, RTX and the case without matches (`OrtcMatchingTest`, `OrtcCapabilitiesTest`, `OrtcSendingTest`, `ScalabilityModeTest` and `H264ProfileLevelIdTest`).
- The results with the fixtures match those of pymediasoup or mediasoup-client, except for the marked bugs. The expected values were obtained by reading the code of mediasoup-client 3.26, without running it.

## Phase 3: SDP

### 3.1 Port of sdp-transform: parser

Origin: the `sdp-transform` package

It goes in an internal package (`0.5.16`), and the parsed SDP is a mutable `Map<String, Object>` that keeps the insertion order (`0.5.12`).

Ported from `clux/sdp-transform` 3.0.0, the version that mediasoup-client 3.26 uses, in `internal.sdptransform` (`Grammar` and `Parser`). The regexes are those of the original; in Java `\s` and `\S` only cover ASCII whitespace.

- [x] `3.1.1` Grammar structure, with the regex, name, fields and format of each line type (`Grammar.Rule`). Formats are functions of the value map, as in the original.
- [x] `3.1.2` Line parser and split between the session and the `m=` sections. It accepts `\r\n`, `\r` and `\n`, and ignores the lines that are not `x=...` with a lowercase letter.
- [x] `3.1.3` Numeric conversion of fields, with the same rule as the original (`String(Number(v)) === v`). Numbers are stored as Jackson reads them: `Integer`, `Long` or `BigInteger` by size, and `Double` if they have decimals.
- [x] `3.1.4` Session lines: `v`, `o`, `s`, `i`, `u`, `e`, `p`, `z`, `r`, `t`, `c`, `b`.
- [x] `3.1.5` The `m=` line with type, port, protocol and payloads.
- [x] `3.1.6` `a=rtpmap`, `a=fmtp`, `a=rtcp` and `a=rtcp-fb`.
- [x] `3.1.7` `a=extmap`, `a=extmap-allow-mixed`.
- [x] `3.1.8` `a=ice-ufrag`, `a=ice-pwd`, `a=ice-lite`, `a=ice-options`.
- [x] `3.1.9` `a=candidate`, `a=end-of-candidates`, `a=remote-candidates`.
- [x] `3.1.10` `a=fingerprint`, `a=setup`, `a=crypto`, `a=connection`.
- [x] `3.1.11` `a=mid`, `a=msid`, `a=msid-semantic`, `a=group`.
- [x] `3.1.12` `a=ssrc`, `a=ssrc-group`.
- [x] `3.1.13` Direction (`sendrecv`, `sendonly`, `recvonly`, `inactive`), `a=rtcp-mux`, `a=rtcp-rsize`.
- [x] `3.1.14` `a=ptime`, `a=maxptime`, `a=framerate`.
- [x] `3.1.15` `a=rid`, `a=simulcast`, and `a=simulcast:` from draft 03.
- [x] `3.1.16` `a=sctp-port`, `a=max-message-size`, `a=sctpmap`.
- [x] `3.1.17` The rest of the attributes of the original grammar (`imageattr`, `source-filter`, `ts-refclk`, `mediaclk`, BFCP...), keeping unknown ones in `invalid`. Also `parseRemoteCandidates`, `parseImageAttributes` and `parseSimulcastStreamList`.
- [x] `3.1.18` `parseParams`. A key without a value is left as `null`, like the `undefined` of the original; the utilities of `3.3` change it to `''`, as mediasoup-client does when validating the capabilities. Converting `profile-level-id` to text also goes in `3.3` (`3.3.2`), because the original does it in `extractRtpCapabilities`, not in `parseParams`.
- [x] `3.1.19` `parsePayloads`, converting like the JavaScript `Number` (`NaN` for anything that is not a number).
- [x] `3.1.20` Port of the parsing test cases of the original library (`ParserTest`, without the writer checks, which go in `3.2.6`, and `CoverageTest`), with their SDPs in `src/test/resources/sdp/`.

### 3.2 Port of sdp-transform: writer

In `internal.sdptransform.Writer`. Like the original, it writes the lines in the order of the grammar and completes the session it receives (`version`, `name` and `payloads`). The JavaScript conversions that the parser and the writer use are in `internal.sdptransform.JavaScript`.

- [x] `3.2.1` Line formatter with the specifiers of the grammar (`%s`, `%d`, `%v`, `%%`). It writes values as JavaScript does: `%d` converts with `Number()`, a missing value is `undefined` and decimals come out with the fewest digits. Integers are written exactly, even beyond 53 bits.
- [x] `3.2.2` Order of the session lines.
- [x] `3.2.3` Order of the lines of each `m=` section.
- [x] `3.2.4` Omit missing fields.
- [x] `3.2.5` `\r\n` line endings.
- [x] `3.2.6` Port of the writing test cases (`ComposeTest`), including the writer checks of `parse.test.js` (`icelite`, `jsep`).
- [x] `3.2.7` Parse and write round trip with the SDPs saved in `0.3.6`: every line is preserved except `a=msid-semantic: WMS` without a token, which the original also rewrites with two spaces.
- [x] `3.2.8` Check that webrtc-java accepts an SDP generated by the port: moved to `5.1.15`, because it needs the natives of `5.1.1`.

### 3.3 Common utilities

Origin: `commonUtils.ts` and `unifiedPlanUtils.ts` of mediasoup-client 3.26 (formerly `pymediasoup/handlers/sdp/common_utils.py`, `unified_plan_utils.py`), in `internal.sdp` (`CommonUtils` and `UnifiedPlanUtils`). An SDP that does not fit the models (a codec without a clock rate, an SSRC of more than 32 bits) throws `IllegalArgumentException`, and the `Error`s of the original throw `MediasoupClientException`.

- [x] `3.3.1` `extractRtpCapabilities`: codecs from all the audio and video sections, indexed by payload type (BUNDLE), like mediasoup-client. ⚠️ do not copy: pymediasoup only reads the first section of each kind.
- [x] `3.3.2` `extractRtpCapabilities`: parameters from `fmtp`, with `profile-level-id` as text and keys without a value (`null` in `parseParams`) as `''`.
- [x] `3.3.3` `extractRtpCapabilities`: `rtcp-fb`; with payload `*` it applies to every codec of the kind except RTX. ⚠️ do not copy: pymediasoup ignores it.
- [x] `3.3.4` `extractRtpCapabilities`: header extensions indexed by kind and id, skipping encrypted ones.
- [x] `3.3.5` `extractDtlsParameters`: `a=setup` and `a=fingerprint` from the session or, if missing, from the first section with a port. An unknown `setup` leaves the role `null`. ⚠️ do not copy: pymediasoup compares with `"activate"`, and the correct value is `"active"`.
- [x] `3.3.6` `getCname`.
- [x] `3.3.7` `getRtpEncodings`: SSRCs and `FID` groups, in SSRC order. Like mediasoup-client, it only adds the RTX SSRC if there is more than one codec. ⚠️ pymediasoup uses a `set`, with no defined order.
- [x] `3.3.8` Tests with SDPs with RTX, without RTX and without SSRC, using the real webrtc-java offers of `0.3.6`.

### 3.4 Media sections

Origin: `RemoteMediaSection.ts` of mediasoup-client 3.26 (formerly `pymediasoup/handlers/sdp/media_section.py`), in `internal.sdp` with its names: `RemoteMediaSection`, `RemoteAnswerMediaSection` and `RemoteOfferMediaSection`. Each section is a mutable sdp-transform media object. The codec options of the answer go in `11.1`, simulcast in `11.3` and the `application` sections in `9.4`. `plainRtpParameters` is not ported: no handler of mediasoup-client uses it.

- [x] `3.4.1` `getCodecName`.
- [x] `3.4.2` `MediaSection` base: ICE and candidates (`component = 1`, `end-of-candidates`, `renomination`), with the address from `effectiveAddress()` and the `tcptype`.
- [x] `3.4.3` `MediaSection`: the `mid` and `closed` (port 0) properties.
- [x] `3.4.4` `MediaSection.setIceParameters`.
- [x] `3.4.5` `MediaSection.disable`, with `pause` and `resume` from mediasoup-client.
- [x] `3.4.6` `MediaSection.close`.
- [x] `3.4.7` `AnswerMediaSection`: header, connection and port.
- [x] `3.4.8` `AnswerMediaSection`: audio and video, `rtp`, `fmtp`, `rtcpFb` and `payloads`.
- [x] `3.4.9` `AnswerMediaSection`: include only the extensions present in the offer.
- [x] `3.4.10` `AnswerMediaSection`: `extmapAllowMixed`, `rtcp-mux`, `rtcp-rsize`.
- [x] `3.4.11` `AnswerMediaSection.setDtlsRole`.
- [x] `3.4.12` `OfferMediaSection`: header, connection, port and protocol.
- [x] `3.4.13` `OfferMediaSection`: audio and video, `msid`, `rtp`, `fmtp`, `rtcpFb` and `ext`.
- [x] `3.4.14` `OfferMediaSection`: `ssrcs` and `ssrcGroups`, with RTX.
- [x] `3.4.15` `OfferMediaSection.setDtlsRole` (always `actpass`).
- [x] `3.4.16` Check the result against `RemoteMediaSection.ts`: the tests compare the written SDP of each section with the one obtained by following the code of mediasoup-client, without running it.

### 3.5 RemoteSdp

Origin: `RemoteSdp.ts` of mediasoup-client 3.26 (formerly `pymediasoup/handlers/sdp/remote_sdp.py`), in `internal.sdp.RemoteSdp`. The `Error`s of the original (a missing section) throw `MediasoupClientException`. `send` with codec options comes in `11.1`, `muxMediaSectionSimulcast` in `11.3` and `sendSctpAssociation` and `receiveSctpAssociation` in `9.4`.

- [x] `3.5.1` Constructor with the base SDP (`origin`, `name`, `timing`) and `a=ice-options:ice2`. The `origin` user is `jmediasoup-v<version>`, with the version the build records in the module descriptor (`jmediasoup` if there is none).
- [x] `3.5.2` `ice-lite` flag.
- [x] `3.5.3` Fingerprint: the last one of the DTLS parameters, like mediasoup-client. ⚠️ do not copy: pymediasoup filters `sha-256` because aiortc only supports that one; libwebrtc accepts the others. Checked with webrtc-java in `5.1.15`.
- [x] `3.5.4` `BUNDLE` group and `msid-semantic`.
- [x] `3.5.5` `updateIceParameters`.
- [x] `3.5.6` `updateDtlsRole`, replacing the DTLS parameters instead of modifying them.
- [x] `3.5.7` `getNextMediaSectionIdx` and `MediaSectionIdx`.
- [x] `3.5.8` `send`, removing the Dependency Descriptor extension except with AV1 or H264.
- [x] `3.5.9` `receive`, recycling only closed sections of the same kind.
- [x] `3.5.10` `disableMediaSection`, with `pauseMediaSection`, `resumeSendingMediaSection` and `resumeReceivingMediaSection` from mediasoup-client.
- [x] `3.5.11` `closeMediaSection`, never closing the first section.
- [x] `3.5.12` `getSdp`, incrementing `sessionVersion`.
- [x] `3.5.13` `addMediaSection`, `replaceMediaSection` and `regenerateBundleMids`.
- [x] `3.5.14` Tests of adding, closing and recycling sections.

**Phase 3 acceptance** (met; the second point, with `5.1.15`)

- The port passes the `sdp-transform` test cases that have been ported.
- `RemoteSdp` generates offers and answers that webrtc-java accepts in a test with local peer connections: it needs the natives, so it is checked in `5.1.15`.

## Phase 4: Device and Transport with FakeHandler

### 4.1 Handler and FakeHandler

Origin: `HandlerInterface.ts` and `FakeHandler.ts` of mediasoup-client 3.26 (formerly `pymediasoup/handlers/handler_interface.py`, `pymediasoup/models/handler_interface.py`, `tests/fake_handler.py`). The contract is the one of mediasoup-client, consistent with `2.4`: the factory gives the native capabilities by direction and creates ready handlers, without `run`. The `FakeHandler` of pymediasoup extends `AiortcHandler` and uses the real stack, so the one of mediasoup-client is ported.

- [x] `4.1.1` `Handler`, with every method of mediasoup-client 3.26 and `CompletableFuture`, in the `handler` package together with its options and results. It includes batched `pauseSending`, `resumeSending`, `receive`, `stopReceiving`, `pauseReceiving` and `resumeReceiving`, and `getDataChannelMaxMessageSize`, which Phase 14 will use without changing the interface. Stats are `RTCStatsReport`, and `setRtpEncodingParameters` takes the `RTCRtpEncodingParameters` of webrtc-java (`0.5.5`).
- [x] `4.1.2` Handler events (`@connect`, `@connectionstatechange`, `@icegatheringstatechange`, `@icecandidateerror`) as a `HandlerListener` that the transport passes in `HandlerOptions`; `connect` returns a `CompletionStage` that the handler waits for. New enum `transport.IceGatheringState`.
- [x] `4.1.3` `HandlerOptions` (formerly `HandlerRunOptions`): direction, ICE, DTLS and SCTP parameters, ICE servers, ICE policy, the `getSendExtendedRtpCapabilities` function and the listener. `additionalSettings` comes with `10.2.8`.
- [x] `4.1.4` `HandlerSendOptions` (with `streamId`) and `HandlerSendResult`, with a `@Nullable RTCRtpSender` (`1.7.2`).
- [x] `4.1.5` `HandlerReceiveOptions` (with `streamId`) and `HandlerReceiveResult`, with the track and a `@Nullable RTCRtpReceiver` (`1.7.3`).
- [x] `4.1.6` `HandlerSendDataChannelOptions`, `HandlerSendDataChannelResult`, `HandlerReceiveDataChannelOptions` and `HandlerReceiveDataChannelResult`, with `handler.DataChannel`.
- [x] `4.1.7` `HandlerFactory`: `name`, `getNativeRtpCapabilities(direction)`, `getNativeSctpCapabilities` (which mediasoup-client 3.26 no longer has, but the `Device.sctpCapabilities` of pymediasoup needs) and `create(options)`.
- [x] `4.1.8` `FakeHandler`: fixed native capabilities (`FakeParameters`) in both directions, and SCTP with `OS` and `MIS` of 1024.
- [x] `4.1.9` `FakeHandler`: `send`, `receive` and the data channels without WebRTC, generating IDs and parameters. It asks the transport to connect before the first send (DTLS role `server`) or receive (`client`). ⚠️ do not copy: the original gives the data channel one id and returns the next one; here they are the same.
- [x] `4.1.10` `FakeHandler`: simulate state changes (`setConnectionState`, `setIceGatheringState`) and failures (`failNextOperation`). Its factory keeps the handlers it creates, so that the tests can drive them.

### 4.2 Device

Origin: `pymediasoup/device.py` and `Device.ts` of mediasoup-client 3.26, in `jmediasoup.Device`. It is thread-safe.

- [x] `4.2.1` Constructor with a `HandlerFactory`.
- [x] `4.2.2` `load`: get the native send and receive capabilities from the `HandlerFactory`.
- [x] `4.2.3` `load`: compute the extended capabilities and the receive and send capabilities (`2.4`), and `canProduce` with `canSend`. The `preferLocalCodecsOrder` of mediasoup-client as an overload, `load(routerRtpCapabilities, preferLocalCodecsOrder)`. If it fails, the device stays unloaded and can be loaded again.
- [x] `4.2.4` `load`: SCTP capabilities, from `HandlerFactory.getNativeSctpCapabilities`.
- [x] `4.2.5` `load`: close the temporary handler on error too: does not apply, because the factory gives the capabilities without creating a handler (`4.1`).
- [x] `4.2.6` Repeated `load`, or with another one in progress: fails with `InvalidStateException`, like mediasoup-client. ⚠️ do not copy: pymediasoup warns and returns.
- [x] `4.2.7` `loaded`, `handlerName`. `handlerName` is available from the start, as in mediasoup-client.
- [x] `4.2.8` `rtpCapabilities` and `sctpCapabilities`, with `InvalidStateException` if not loaded. Also `recvRtpCapabilities` and `sendRtpCapabilities` from mediasoup-client; `rtpCapabilities` is the same as `recvRtpCapabilities`.
- [x] `4.2.9` `canProduce(MediaKind)`.
- [x] `4.2.10` `createSendTransport`: moved to `4.3.11`, because it needs `Transport`.
- [x] `4.2.11` `createRecvTransport`: moved to `4.3.12`.
- [x] `4.2.12` `observer` with `newtransport`: moved to `4.3.13`.

### 4.3 Basic Transport

Origin: `pymediasoup/transport.py`

- [x] `4.3.1` Constructor: create the handler with `HandlerFactory.create`, passing it a `HandlerListener` that brings its events to the transport queue. It receives the internal options (`InternalTransportOptions` in the original, formerly `1.5.10`): direction, `HandlerFactory`, receive RTP capabilities, the function that computes the extended send capabilities from the native ones (`getSendExtendedRtpCapabilities` in mediasoup-client) and `canProduceByKind`. Done as a sealed `Transport`, with `SendTransport` and `RecvTransport` (see Decisions). It fills in the SCTP message sizes that old servers do not send, like mediasoup-client. `canProduceByKind` and the receive capabilities come with `produce` (`4.4`) and `consume` (`4.5`), which are the ones that use them.
- [x] `4.3.2` Serialized queue per transport for the handler operations and the delivery of events (`0.5.2`). ⚠️ do not copy: the original has no queue (`NOTE: Mediasoup client enqueue command here`). Java has real concurrency. Done with `SerialExecutor` and `OperationQueue` in `internal.event` (see Decisions); the transport uses them since `4.3.1`.
- [x] `4.3.3` Reject the pending operations of the queue on close: `OperationQueue.stop`, with `InvalidStateException("closed")`. The running operation is not interrupted, but its result is discarded.
- [x] `4.3.4` Properties `id`, `closed`, `direction`, `handler`, `connectionState` and `appData`. Plus `iceGatheringState` from mediasoup-client (formerly `14.1.4`). `appData` has no setter.
- [x] `4.3.5` `connect` listener, required when creating the transport, with a future: the `ConnectHandler` interface, called with `AsyncHandlers.run`. ⚠️ do not copy: the original fires `connect` and carries on without waiting for the signaling to finish. Fails with `InvalidStateException` if the transport is closed.
- [x] `4.3.6` `connectionstatechange` listener. Plus `icegatheringstatechange` and `icecandidateerror` from mediasoup-client. A repeated state is not emitted, and nothing is emitted after `close`.
- [x] `4.3.7` `produce` listener, required when creating the send transport, which returns the producer ID: the `ProduceHandler` interface with a request record, called with `AsyncHandlers.call`. An empty ID also fails. Done: `ProduceHandler` and `ProduceRequest`; the call and the ID check come with `produce` in `4.4`.
- [x] `4.3.8` `close`: close the handler and notify every producer and consumer. It stops the queue, closes the handler and sets the state to `closed` without an event. Notifying producers and consumers comes with `4.4.3` and `4.5.3`.
- [x] `4.3.9` `observer` with `close`, `newproducer` and `newconsumer`. `TransportObserver` with `onClose`; `newproducer` and `newconsumer` come with `4.4` and `4.5`.
- [x] `4.3.10` `additionalSettings`: moved to `10.2.8`.
- [x] `4.3.11` `Device.createSendTransport` (formerly `4.2.10`).
- [x] `4.3.12` `Device.createRecvTransport` (formerly `4.2.11`). ⚠️ do not copy: the original only accepts a `dict` in recv. Both variants must accept the same input types. Both take `TransportOptions`.
- [x] `4.3.13` `Device.observer` with `newtransport` (formerly `4.2.12`). ⚠️ do not copy: the original documents it but never emits it. `DeviceObserver.onNewTransport`, emitted on the executor of the new transport before any other of its events.

### 4.4 Basic Producer and Transport.produce

Origin: `pymediasoup/producer.py`, `pymediasoup/transport.py`

- [x] `4.4.1` `Producer`: properties `id`, `localId`, `closed`, `kind`, `rtpSender`, `track`, `rtpParameters`, `paused`, `maxSpatialLayer` and `appData`. `paused` starts as `true` if the track is disabled and `disableTrackOnPause` is on, as in mediasoup-client; `paused` and `maxSpatialLayer` change from phases 8 and 11 on. `appData` has no setter, as in the transport.
- [x] `4.4.2` `Producer.close`. The `close` observer is emitted on the transport executor.
- [x] `4.4.3` `Producer.transportClosed` and the `transportclose` event (`onTransportClose`). It is delivered before the `close` observer of the transport.
- [x] `4.4.4` `Transport.produce`: track, direction and `canProduce` checks. ⚠️ do not copy: the original does not check whether the transport is closed. In `SendTransport.produce`, so the type guarantees the direction. They fail in the future: `InvalidStateException` if the transport is closed or the track has ended, `UnsupportedException` if that kind cannot be sent.
- [x] `4.4.5` `createSendTransport` requires the `connect` and `produce` listeners, and `createRecvTransport` only `connect` (`0.5.3`). ⚠️ do not copy: the second check of the original in `produce` looks at `connect`. Done in `4.3`: the handlers are required parameters.
- [x] `4.4.6` `Transport.produce`: flow with the handler and the listener, and registration of the producer. Like mediasoup-client: it normalizes the encodings, calls `handler.send` in the queue and the `ProduceHandler` on the executor, and calls `stopSending` if it fails. An empty ID fails. If the transport closes while the server creates the producer, it fails with `InvalidStateException` and does not register it. `validateAndNormalizeRtpParameters` is not ported: the records already validate, and the server sets the defaults.
- [x] `4.4.7` If it fails because the transport is closed, stop the track (a pending `TODO` in the original): moved to `8.2.12`, with the rest of `stopTracks`.
- [x] `4.4.8` Connect the `@close` of the producer to `handler.stopSending`, in the queue; a failure is only logged.
- [x] `4.4.9` `Producer.observer` with `close`. Plus the `newproducer` observer of the transport.

### 4.5 Basic Consumer and Transport.consume

Origin: `pymediasoup/consumer.py`, `pymediasoup/transport.py`

- [x] `4.5.1` `Consumer`: properties `id`, `localId`, `producerId`, `closed`, `kind`, `rtpReceiver`, `track`, `rtpParameters`, `paused` and `appData`. `kind` comes from `ConsumerOptions`, and `paused` starts as `true` if the track is disabled, as in mediasoup-client. `appData` has no setter.
- [x] `4.5.2` `Consumer.close`. The `close` observer is emitted on the transport executor.
- [x] `4.5.3` `Consumer.transportClosed` and the `transportclose` event (`onTransportClose`).
- [x] `4.5.4` `Transport.consume`: checks and `canReceive`. In `RecvTransport.consume`; `ConsumerOptions` validates the fields. They fail in the future: `InvalidStateException` if the transport is closed, `UnsupportedException("cannot consume this Producer")` if `canReceive` does not allow it.
- [x] `4.5.5` `Transport.consume`: flow with the handler and registration of the consumer. ⚠️ do not copy: the original does not pass `rtpReceiver` to the `Consumer`. In batches, like mediasoup-client 3.26 (formerly `14.1.1`): the `consume` calls that arrive while another operation is running are negotiated together in a single `handler.receive`. If the batch fails, all its `consume` calls fail. Closing the transport fails the pending ones and those the handler is receiving, without waiting for it. The probator goes in `8.3`.
- [x] `4.5.6` Connect the `@close` of the consumer to `handler.stopReceiving`. Also in batches (`closePendingConsumers`); a failure is only logged.
- [x] `4.5.7` `Consumer.observer` with `close`. Plus the `newconsumer` observer of the transport.

### 4.6 Port of the test suite

Origin: `tests/__init__.py`, and `src/test/test.ts` of mediasoup-client, whose `FakeHandler` is ported. The tests of `test.ts` that touch features of later phases (pause, `replaceTrack`, stats, ICE, data channels) go with them. The ones that check argument types or the transport direction do not apply: the records and the `SendTransport` and `RecvTransport` types guarantee them.

- [x] `4.6.1` `test_create_device`: `DeviceTest.aNewDeviceIsNotLoaded`.
- [x] `4.6.2` `test_device_load`: `DeviceTest.loadSucceeds` and the error cases.
- [x] `4.6.3` `test_device_rtp_capabilities`: `DeviceTest.loadSucceeds`.
- [x] `4.6.4` `test_device_sctp_capabilities`: `DeviceTest.loadSucceeds`.
- [x] `4.6.5` `test_device_can_produce_audio` and `test_device_can_produce_video`: `DeviceTest.canProduceAudioAndVideo`.
- [x] `4.6.6` `test_send_transport`: `TransportTest.createSendTransportSucceeds`.
- [x] `4.6.7` `test_recv_transport`: `TransportTest.createRecvTransportSucceeds`. The `produce` part on a receive transport does not apply: `RecvTransport` has no `produce`.
- [x] `4.6.8` `test_produce`, without the data channel part: `ProducerTest`, with the expected RTP parameters in `theRtpParametersAreThoseOfPymediasoup`. Without the defaults pydantic fills in (an empty `parameter` in `rtcpFeedback`), which stay `null` (`1.3`).
- [x] `4.6.9` `test_consume`, without the data channel part: `ConsumerTest`, with the expected RTP parameters in `theRtpParametersAreThoseOfPymediasoup`, also without the defaults of pydantic (`encrypt`).
- [x] `4.6.10` Tests of concurrent operations on the queue: `OperationQueueTest`, `SerialExecutorTest`, `TransportTest.listenersRunOneAtATimeOnTheExecutor`, the order of `produce` and the `consume` batches, and `produce` and `consume` from 20 threads at once.
- [x] `4.6.11` Tests of closing with operations in progress: closing while the server creates a producer, while the handler receives, with pending `consume` calls and with 20 `produce` calls running, without any of them hanging.

**Phase 4 acceptance** (met)

- The ported tests pass without loading natives.
- Concurrent operations on a transport run in order.

## Phase 5: WebRtcJavaHandler, receiving

Testing against a real server needs sections 7.1 and 7.2, so they have to be brought forward before closing this phase.

### 5.1 Handler base

Origin: `pymediasoup/handlers/aiortc_handler.py`

- [x] `5.1.1` Add `webrtc-java` as `api`. The natives only for the tests: in real use the user declares them (`0.5.7`). It was already `api` since Phase 1; the natives are `testRuntimeOnly`, with the classifier picked from `os.name` and `os.arch`.
- [x] `5.1.2` Shared management of the `PeerConnectionFactory`, with its lifecycle and closing. `WebRtcJavaHandler.Factory`, with the two variants of `createFactory` (see Decisions). Once it is closed, `getNativeRtpCapabilities` fails with `InvalidStateException`.
- [x] `5.1.3` Adapters of the interfaces of section 1.7 over webrtc-java: moved to `9.4.12`, because only the data channel one is left (the sender and receiver ones were discarded in `1.7.2` and `1.7.3`).
- [x] `5.1.4` Turn the `createOffer`, `createAnswer`, `setLocalDescription` and `setRemoteDescription` callbacks into futures: `PeerConnections`, which completes them on a virtual thread and not on the signaling thread.
- [x] `5.1.5` The `HandlerFactory` of `WebRtcJavaHandler`, with `name` (`WebRtcJava`). `create` fails until `5.1.8`.
- [x] `5.1.6` `getNativeRtpCapabilities` by direction (`sendonly` or `recvonly`), with a temporary peer connection (per `0.3.17`). Like `Chrome111`: the video transceiver has `scalabilityMode` `L3T3` so that the Dependency Descriptor extension shows up, and NACK is added to Opus (`OrtcUtils.addNackSupportForOpus`). `validateAndNormalizeRtpCapabilities` is not ported, because the records already validate, nor `forcedRtpExtensions`, because webrtc-java has no `setHeaderExtensionsToNegotiate`.
- [x] `5.1.7` `getNativeSctpCapabilities` (`OS` and `MIS` of 1024).
- [x] `5.1.8` Constructor: `RemoteSdp` and sending parameters by kind. The sending parameters are not computed here: as in mediasoup-client 3.26, `send` asks for them with `getSendExtendedRtpCapabilities` (`6.2.14`). The local DTLS role is the opposite of the remote one if mediasoup sets it, and `client` if it is `auto`. The methods of later phases fail with `UnsupportedException` until then, and `getDataChannelMaxMessageSize` gives `null` because webrtc-java does not expose the SCTP transport.
- [x] `5.1.9` Constructor: peer connection configuration with `iceServers` and `iceTransportPolicy`, plus `max-bundle` and required `rtcp-mux`, like `Chrome111`.
- [x] `5.1.10` Map the ICE state to `ConnectionState`. With the peer connection state (`onConnectionChange`), as `Chrome111` does when the browser has it, and not with the ICE one. Also the ICE gathering state and the candidate errors. The transport is notified from the signaling thread, because the transport only hands them to its executor, which keeps them in order.
- [x] `5.1.11` `close`, releasing the native resources: it closes the peer connection. Closing the factory closes the handlers that are still open.
- [x] `5.1.12` `setupTransport`: extract DTLS, set the role, notify through `@connect` and wait for the result.
- [x] `5.1.13` `assertSendDirection` and `assertRecvDirection`.
- [x] `5.1.14` Test with a real `Device.load`: the capabilities include Opus and VP8. `WebRtcJavaHandlerFactoryTest`.
- [x] `5.1.15` Check that webrtc-java accepts an SDP generated by the writer of `3.2` (formerly `3.2.8`), and the offers and answers of `RemoteSdp` in local peer connections (Phase 3 acceptance), with the `sha-512` fingerprint that `RemoteSdp` picks. `WebRtcJavaHandlerTest`: a receive offer with audio and video, and an answer to a send offer with audio and video.

### 5.2 Receiving

- [x] `5.2.1` `receive`: assign the `localId`. ⚠️ do not copy: `str(len(map))` can collide after a `stopReceiving`. Use a counter, like mediasoup-client. The `localId` is the MID that mediasoup gives the consumer, as in mediasoup-client 3.26; if there is none, a counter that skips the ones in use (3.26 uses the size of the map, which has the same problem).
- [x] `5.2.2` `receive`: remote offer with `RemoteSdp.receive`, several sections in one negotiation (the batches of `4.5.5`).
- [x] `5.2.3` `receive`: `setRemoteDescription` and `createAnswer`.
- [x] `5.2.4` `receive`: `setupTransport` with the `client` role the first time, or the opposite of the one mediasoup sets.
- [x] `5.2.5` `receive`: `setLocalDescription` and find the transceiver by `mid`.
- [x] `5.2.6` `applyCodecParameters` for Opus. ⚠️ do not copy: the original looks for `rmtp` instead of `fmtp`. In `CommonUtils`, like mediasoup-client: `stereo` from the `sprop-stereo` of the offer. ⚠️ do not copy: mediasoup-client writes `key=undefined` for a parameter without a value; here the key is written alone.
- [x] `5.2.7` Return the track and the receiver wrapped in the abstractions: unwrapped, those of webrtc-java (`1.7.2`, `1.7.3`). The id of the remote track is the consumer id.
- [x] `5.2.8` `stopReceiving`: close the section and renegotiate. The first section is disabled instead of closed, because it carries the BUNDLE transport; the others are closed and webrtc-java removes their transceiver.
- [x] `5.2.9` E2E test: receive audio from a producer of the server. The server only consumes for a peer the producers of the other peers, and jmediasoup cannot send yet, so an ffmpeg container publishes the media as a broadcaster of the demo, through its REST API and a `PlainTransport` per stream (`Broadcaster`): `mwader/static-ffmpeg` 9.0.2, pinned by digest, which generates a 440 Hz tone and a test pattern itself. `ReceiveTest`: the audio sink gets the tone in Opus, and the server stats show the RTP sent to the consumer.
- [x] `5.2.10` E2E test: receive video from a producer of the server, from the same ffmpeg container. `ReceiveTest`: the video sink gets frames of the test pattern, at its size, in VP8 and in H264 (Constrained Baseline), with a key frame every second because ffmpeg ignores the key frame requests.
- [x] `5.2.11` Test: `connectionState` reaches `connected` and `closed`. `DemoPeerTest`: a receive transport consumes the producer of another peer, without media, and connects to the server through ICE and DTLS.
- [x] `5.2.12` `receive`: use the `streamId` of `ConsumerOptions` (or the `producerId` if missing) as the `msid` stream, like mediasoup-client. In 3.26, if missing, it is the stream of the `msid` from mediasoup, its CNAME or `-` (`OrtcUtils.getMsidStreamIdAndTrackId`), not the `producerId`.

**Phase 5 acceptance**

- The sinks receive real audio and video from the mediasoup server (met: `5.2.9` and `5.2.10`).
- After closing, no threads or native resources are left alive. Not checked yet: it goes with the leak test of `13.2.5`.

## Phase 6: WebRtcJavaHandler, sending

### 6.1 Test sources

Origin: the `AudioStreamTrack` and `VideoStreamTrack` of aiortc, used in `examples/mediasoup.py`

They are test fixtures (`src/testFixtures`, the `java-test-fixtures` plugin), shared by `test` and `integrationTest` and left out of the publication, as in pymediasoup, where the sources come from aiortc and not from the library. They are a module of their own, `io.github.hectorespert.jmediasoup.testfixtures`, which the whitebox tests require.

- [x] `6.1.1` Silent audio source on `CustomAudioSource`: a 440 Hz tone instead, `ToneAudioSource`, 16-bit mono PCM at 48 kHz, so that the receiver can tell that audio arrives (silence is what libwebrtc plays when nothing arrives).
- [x] `6.1.2` Video source with fixed-color frames on `CustomVideoSource`: `ColorVideoSource`, 320x240 in green.
- [x] `6.1.3` Steady timing of both sources: each one pushes from its own thread at a fixed rate, 10 ms of audio and 30 frames per second. `TestMediaSourcesTest` checks the rate, the size, the color and the tone through local tracks.
- [x] `6.1.4` Stop the sources on close: `close()` stops the thread and disposes the native source.

### 6.2 Sending

- [x] `6.2.1` `send`: copy and reduce the RTP parameters according to the codec.
- [x] `6.2.2` `send`: `addTransceiver` in `sendonly`, with the given encoding. More than one encoding fails with `UnsupportedException` until simulcast (`11.3.1`), and the codec options are ignored with a warning until `11.1.13`.
- [x] `6.2.3` `send`: `createOffer` and `setupTransport` the first time, with the DTLS role that mediasoup forces or else `client`, as mediasoup-client 3.26 does (pymediasoup uses `server`).
- [x] `6.2.4` `send`: `setLocalDescription` and get the `mid`.
- [x] `6.2.5` `send`: find the offer section by `mid` instead of relying only on the index.
- [x] `6.2.6` `send`: CNAME and encodings from the local SDP.
- [x] `6.2.7` `send`: `RemoteSdp.send` and `setRemoteDescription`.
- [x] `6.2.8` `send`: reuse the `mid` of a closed section.
- [x] `6.2.9` A real `stopSending`. ⚠️ do not copy: in the original it does nothing because aiortc has no `removeTrack`.
- [x] `6.2.10` E2E test: send audio and check on the server that RTP arrives.
- [x] `6.2.11` E2E test: send video and check on the server that RTP arrives.
- [x] `6.2.12` E2E test: close a producer and create another one on the same transport.
- [x] `6.2.13` `send`: use the `streamId` of `ProducerOptions` as the `msid` stream, to synchronize the tracks that share it.
- [x] `6.2.14` `send`: compute the extended send capabilities with the local capabilities of the offer, like mediasoup-client, and not with those of `load`.
- [x] `6.2.15` A failed `send` completes the negotiation with its section closed, as `stopSending` does, and the next one reuses it. ⚠️ do not copy: mediasoup-client rolls the offer back and stops the transceiver, but libwebrtc keeps the index of the section that the offer gave the stopped transceiver, and gives it the section of the next `send` with the same index, whose transceiver then has no MID. The rollback is kept as a last resort if closing the section fails.

**Phase 6 acceptance**

- The server receives audio and video RTP from jmediasoup.
- Producers and consumers can be created and closed repeatedly on the same transport.

## Phase 7: MVP milestone

### 7.1 Integration environment

- [x] `7.1.1` Add Testcontainers to the version catalog: 2.0.5, only the base module. The JUnit extension is our own (`MediasoupServer`), so as not to mix the one of Testcontainers with JUnit 6.
- [x] `7.1.2` Create an `integrationTest` source set or task, separate from `test`: a `JvmTestSuite` in `src/integrationTest`, on the classpath (`javaModuleTesting.classpath`), which `check` depends on.
- [x] `7.1.3` Make the task compatible with the configuration cache.
- [x] `7.1.4` Start the image chosen in `0.4.7` by digest, and wait until it is ready: it waits for `Server started` in the log. A single container for all the tests, without capabilities.
- [x] `7.1.5` Configure the network and `announcedAddress` per `0.4.8` and `0.4.9`: host network, with mediasoup listening on and announcing `127.0.0.1`. Linux only; macOS and Windows wait for `0.4.9`. The demo runs one worker per CPU on `44444` and the next ports, in the ephemeral range of Linux (32768–60999), and in CI another socket of the runner once took `44446` first; the container rewrites its config to run one worker on `24444`, with the plain transports on `20000`–`20999`.
- [x] `7.1.6` Skip the tests with a clear message if Docker is not available.
- [x] `7.1.7` Collect the container logs if a test fails: they are written to the error output of the test, which the report collects.

### 7.2 Test signaling

This client is only for the tests and adapts to the signaling of the chosen image: protoo over a WebSocket (section 0.4). It lives in `src/integrationTest`: `ProtooClient` speaks protoo, and `DemoPeer` has a method for each request of the demo, returning the jmediasoup models.

- [x] `7.2.1` WebSocket or HTTP client, depending on the image: `ProtooClient`, on `java.net.http.WebSocket` and Jackson 3, with requests, responses and notifications in both directions. It rejects the requests it has no handler for, such as the `newDataConsumer` of the bot of the demo.
- [x] `7.2.2` Get the RTP capabilities of the router: `DemoPeer.routerRtpCapabilities`, which loads a `Device` of `WebRtcJavaHandler`.
- [x] `7.2.3` Create WebRTC transports: `DemoPeer.createWebRtcTransport`, with the direction in `appData.direction`, as `TransportOptions`.
- [x] `7.2.4` Connect transports (`connect`): `DemoPeer.connectWebRtcTransport`, for the `ConnectHandler`.
- [x] `7.2.5` Create a producer on the server (`produce`): `DemoPeer.produce`. The server creates the producer even if no media arrives.
- [x] `7.2.6` Create and resume a consumer on the server: the server creates it, paused, for every producer of the other peers once the peer has joined and has a consumer transport, and resumes it when the peer accepts the `newConsumer` request. `DemoPeer.nextConsumer` hands it over as `ConsumerOptions`, and the test accepts it after consuming it.
- [x] `7.2.7` Query the server stats to check that RTP arrives: `DemoPeer.transportStats`, `producerStats` and `consumerStats`, the stats of mediasoup as JSON. Checking that RTP arrives comes with media, in `5.2.9`, `5.2.10` and `6.2.10`.

### 7.3 Loopback E2E test

- [x] `7.3.1` Load the `Device` with the router capabilities.
- [x] `7.3.2` Create send and recv transports.
- [x] `7.3.3` Produce audio and video.
- [x] `7.3.4` Consume what was produced, in the same process. The demo server only consumes the producers of other peers, so a second peer, with its own `Device` and handler factory, consumes them.
- [x] `7.3.5` Check that the audio sink receives the 440 Hz tone, from its zero crossings.
- [x] `7.3.6` Check that the video sink receives 320x240 frames with the luma of the green.
- [x] `7.3.7` Check the `connectionState` transitions.
- [x] `7.3.8` Close everything and check the final state.
- [x] `7.3.9` Run the test several times in a row to detect flakiness: it is a `@RepeatedTest(3)`.

### 7.4 MVP delivery

- [x] `7.4.1` Run `integrationTest` in CI: `check` depends on it, so the `build` of the CI runs it.
- [x] `7.4.2` ~~Minimal runnable example.~~ Dropped: the project does not include an example; the usage section of the README (`7.4.3`) shows the code.
- [x] `7.4.3` Usage section in the README, with the supported platforms and the limitations. The usage examples talk to the server through a generic `signaling`, the platforms are the classifiers of webrtc-java with what CI tests, and the installation already shows `0.1.0`.
- [x] `7.4.4` Compute the version from git with axion-release: an `X.Y.Z` tag gives `X.Y.Z`, and any other commit the next minor version with `-SNAPSHOT` (`incrementMinor`). The tags have no prefix, like those of the GitHub releases of the other Java libraries of the author; with an empty prefix, axion fails the build if any tag is not SemVer, such as `v0.5.0`. Checked with the configuration cache, in a shallow clone as on GitHub Actions, and on a tag checkout. A pre-release tag such as `0.1.1-rc1` gives that version. It replaces the fixed version of `0.2.2`.
- [x] `7.4.5` Generate the sources and Javadoc jars, which `assemble` builds and the publication includes. The sources jar has all the code, internal packages included, so that a debugger can step into them; the Javadoc only the exported packages. The Javadoc links the types of the JDK 21 and webrtc-java to their Javadoc (docs.oracle.com and javadoc.io), so generating it needs those sites to answer, and any warning fails it (`-Xwerror`).
- [x] `7.4.6` Complete the POM metadata (name, description, URL, inception year, license, developers, SCM, issue tracker), and include `LICENSE` and `NOTICE` in `META-INF/` of the jars, which did not carry them. MIT and ISC require the notice and the license of the originals to go with every copy, so all the jars carry them, sources and Javadoc included. The POM declares only Apache-2.0, the licence of jmediasoup, and the developer without email.
- [x] `7.4.7` GPG signing in the build, with Gradle's `signing` plugin and the armored key in memory: the `signingInMemoryKey` and `signingInMemoryKeyPassword` properties, which CI passes as `ORG_GRADLE_PROJECT_*` variables, without gpg installed. Signing is required when there is a key or the version is a release, so a snapshot without a key is published to Maven local unsigned and a release without a key fails. Checked with a throwaway key: `gpg --verify` accepts the signatures of the jar, sources, Javadoc, POM and Gradle module, and a release tag without a key fails with no signatory.
- [x] `7.4.8` Publish to Central Portal from the build with nmcp (GradleUp, MIT), since Sonatype has no official Gradle plugin: it uploads the signed publication as it is, so it keeps the jars and POM of `7.4.5` and `7.4.6`, which vanniktech's plugin would have replaced with its own DSL. `publishAggregationToCentralPortal` uploads with the portal token in `mavenCentralUsername` and `mavenCentralPassword`, and the publishing type is `AUTOMATIC`: a deployment that passes validation is published without a manual step. The aggregation plugin goes in the library project, because nmcp refuses two projects with the same name and the root project is also `jmediasoup`. Checked with `nmcpZipAggregation` (signatures and checksums of every file); the upload itself waits for `7.4.10`.
- [ ] `7.4.9` Release workflow when a GitHub release is published, as in the other Java libraries of the author, with the tag `X.Y.Z` of the release as the version. It checks that axion computes the version of the tag, runs the whole build, integration tests included, and publishes to the Central Portal. Pre-releases are not published. The secrets are those of the other libraries (`MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`, `OSSRH_USERNAME` and `OSSRH_PASSWORD`, the portal token), in a `maven-central` environment. It listens to `published` rather than `created`, which also fires when a draft is saved. The author creates the workflow from the web UI.
  Pending steps of the author, in the GitHub web UI (the token of the assistant cannot write workflows):
  - [ ] Create the `maven-central` environment (Settings, Environments), limited to tags with the pattern `*.*.*`.
  - [ ] Add to it the four secrets, with the same values as in the other libraries (the same GPG key and portal token): `MAVEN_GPG_PRIVATE_KEY` (the armored private key), `MAVEN_GPG_PASSPHRASE`, `OSSRH_USERNAME` and `OSSRH_PASSWORD`.
  - [ ] Create `.github/workflows/release.yml` with this content:

    ```yaml
    name: Release

    # Publishes to Maven Central the version of a GitHub release, whose tag is X.Y.Z, once the build of the
    # tagged commit passes. Pre-releases are not published.
    on:
      release:
        types: [published]

    permissions:
      contents: read

    concurrency:
      group: ${{ github.workflow }}
      cancel-in-progress: false

    jobs:
      publish:
        if: ${{ !github.event.release.prerelease }}
        runs-on: ubuntu-24.04
        timeout-minutes: 30
        # The signing key and the Central Portal token are secrets of this environment.
        environment: maven-central
        steps:
          - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
            with:
              persist-credentials: false

          - uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0
            with:
              distribution: temurin
              java-version: 21

          - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
            with:
              cache-provider: basic

          # axion-release computes the version from the tag of the release; stop if they do not match.
          - name: Check the version
            run: |
              version=$(./gradlew -q currentVersion | sed -n 's/^Project version: //p')
              if [ "$version" != "$GITHUB_REF_NAME" ]; then
                echo "::error::the version is '$version', but the tag of the release is '$GITHUB_REF_NAME'"
                exit 1
              fi

          - name: Build and publish to Maven Central
            run: ./gradlew build publishAggregationToCentralPortal
            env:
              ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
              ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
              ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.OSSRH_USERNAME }}
              ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.OSSRH_PASSWORD }}

          - name: Upload test reports
            if: ${{ failure() }}
            uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
            with:
              name: test-reports
              path: |
                jmediasoup/build/reports/tests/
                jmediasoup/build/test-results/
              if-no-files-found: ignore
              retention-days: 14
    ```

  If the GPG key signs with a subkey instead of the primary key, the build also needs its id (`useInMemoryPgpKeys(keyId, key, password)`); the first release will tell.
- [ ] `7.4.10` Publish version `0.1.0` to Maven Central and try it from a clean project. The author publishes the GitHub release with the tag `0.1.0` on `main`, not as a pre-release, once `7.4.9` is in place.

**MVP acceptance**

- The loopback test passes steadily in CI.
- It meets the [MVP definition](#summary).
- A new project can use `0.1.0` from Maven Central.

---

# Part B: from the MVP to parity

## Phase 8: media control

### 8.1 Pause and resume

Origin: `producer.py`, `consumer.py`

- [x] `8.1.1` `Producer.pause`: disable the track. ⚠️ do not copy: the original does not, because the aiortc track has no `enabled`. `pause()` and `resume()` return nothing, as in mediasoup-client 3.26 and like `close()`: the state and the track change at once, and the work of the handler goes to the queue of the transport, where a failure is only logged. They do nothing on a closed producer.
- [x] `8.1.2` `Producer.resume`.
- [x] `8.1.3` `disableTrackOnPause`.
- [x] `8.1.4` `zeroRtpOnPause`: as in mediasoup-client 3.26, the transceiver goes `inactive` on pause and `sendonly` on resume (`pauseSending` and `resumeSending` of the handler, which renegotiate). ⚠️ do not copy: the original emits `@replacetrack` without an argument.
- [x] `8.1.5` `Producer.observer` with `pause` and `resume`.
- [x] `8.1.6` `Consumer.pause`, as in mediasoup-client 3.26: it disables the track, and the transport pauses together, in one `pauseReceiving` of the handler, the consumers paused while another operation is in progress; a resume before the pause runs cancels it, and so does closing the consumer. Pausing twice does nothing. ⚠️ do not copy: in the original `_disableTrackOnPause` is always `false` and does nothing.
- [x] `8.1.7` `Consumer.resume`, batched in `resumeReceiving` in the same way.
- [x] `8.1.8` `Consumer.observer` with `pause` and `resume`.
- [x] `8.1.9` E2E test: the server stops receiving RTP when pausing with `zeroRtpOnPause`, and receives it again on resume.

### 8.2 Track replacement and end

- [x] `8.2.1` `Producer.replaceTrack`: checks and the same track. It returns a `CompletableFuture<Void>`, as the promise of mediasoup-client, which fails with `InvalidStateException` if the producer is closed, before or while replacing, or the track has ended, and completes at once for the same track.
- [x] `8.2.2` `Producer.replaceTrack`: allow a null track, as the comment of the original says. `Producer.track()` is then `null`, and the server stops receiving RTP until another track.
- [x] `8.2.3` ~~`Producer.replaceTrack`: stop the previous track according to `stopTracks`.~~ Nothing to do after `8.2.13`.
- [x] `8.2.4` `Producer.replaceTrack`: apply the pause state to the new track.
- [x] `8.2.5` ~~If the producer is closed, stop the received track according to `stopTracks`.~~ Nothing to do after `8.2.13`.
- [x] `8.2.6` `handler.replaceTrack`: `sender.replaceTrack`, without renegotiating. An E2E test checks that the server stops receiving with no track and receives again with a new one.
- [x] `8.2.7` Connect the `@replacetrack` of the producer to the handler, through the queue of the transport.
- [x] `8.2.8` ~~`stopTracks` on close.~~ Nothing to do after `8.2.13`.
- [x] `8.2.9` `trackended` event and observer on `Producer`, on the executor of the transport. webrtc-java only ends local tracks on `dispose()` (`0.3.10`), so in practice it comes from custom tracks.
- [x] `8.2.10` `trackended` event and observer on `Consumer`.
- [x] `8.2.11` Remove the track listener when destroying it: on close, on the close of the transport and when replacing the track.
- [x] `8.2.12` ~~If `produce` fails because the transport is closed, stop the track according to `stopTracks` (formerly `4.4.7`).~~ Nothing to do after `8.2.13`.
- [x] `8.2.13` Decide what stopping a track means in webrtc-java, which has no `stop()` (`0.3.10`). Decided: nothing; the track belongs to the application, which created it with its `PeerConnectionFactory` and may use it elsewhere (a preview, another producer), and releases it. `stopTracks` stays in the options for parity, documented as having no effect. Checked with the natives: a second `dispose()`, or any method after it (`isEnabled`, `getState`), throws `NullPointerException` ("Object handle is null"), and `dispose()` on a track that a sender still holds throws `java.lang.Error` ("Native object was not deleted"), so the library could only release it after `stopSending`, leaving the references of the application unusable. Neither are the remote tracks of the consumers released: their receivers own them. So `8.2.3`, `8.2.5`, `8.2.8` and `8.2.12` do nothing.

### 8.3 RTP probator and recycling

- [ ] `8.3.1` Create the probation consumer with the first video consumer.
- [ ] `8.3.2` Check that it is not created more than once, also with concurrent calls.
- [ ] `8.3.3` Decide whether a failure of the probator should make `consume` fail. mediasoup-client only logs the error.
- [ ] `8.3.4` E2E test of section recycling: create and close consumers repeatedly.
- [ ] `8.3.5` E2E test of recycling an audio section with a video one.
- [ ] `8.3.6` E2E test: close the consumer of the first section (it is disabled, not closed).
- [ ] `8.3.7` Publish version `0.2.0`.

**Phase 8 acceptance**

- `pause` and `resume` really affect the media, as checked from the server.
- `replaceTrack` does not interrupt the session.

## Phase 9: data channels

### 9.1 DataProducer

Origin: `pymediasoup/data_producer.py`

- [ ] `9.1.1` Properties `id`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol` and `appData`.
- [ ] `9.1.2` `bufferedAmount` and `bufferedAmountLowThreshold` (read and write).
- [ ] `9.1.3` Text `send`.
- [ ] `9.1.4` Binary `send`.
- [ ] `9.1.5` `close` and `transportClosed`.
- [ ] `9.1.6` `open`, `error`, `bufferedamountlow` and `transportclose` events.
- [ ] `9.1.7` Close started by the channel: mark it as closed and notify.
- [ ] `9.1.8` Discard and log incoming messages.
- [ ] `9.1.9` `observer` with `close`.

### 9.2 DataConsumer

Origin: `pymediasoup/data_consumer.py`

- [ ] `9.2.1` Properties `id`, `dataProducerId`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol` and `appData`.
- [ ] `9.2.2` `binaryType` (read and write), or an idiomatic Java alternative. `DataChannelMessage` already offers bytes and text, so documenting that it is not needed is probably enough.
- [ ] `9.2.3` `close` and `transportClosed`.
- [ ] `9.2.4` `open`, `error`, `message` and `transportclose` events.
- [ ] `9.2.5` Close started by the channel.
- [ ] `9.2.6` `observer` with `close`.

### 9.3 Transport

Origin: `pymediasoup/transport.py`

- [ ] `9.3.1` `producedata` listener, optional when creating the send transport because not everyone uses data channels: the overload `createSendTransport(options, connect, produce, produceData)`; without it, `produceData` fails with `InvalidStateException`.
- [ ] `9.3.2` `produceData`: checks (direction, SCTP enabled, `producedata` listener present).
- [ ] `9.3.3` `produceData`: `ordered = false` if there is a `maxPacketLifeTime` or `maxRetransmits`, and `true` if there is neither (`validateAndNormalizeSctpStreamParameters` of mediasoup-client). ⚠️ do not copy: the original checks whether they are truthy, so a value of 0 is ignored.
- [ ] `9.3.4` `produceData`: flow with the handler and registration.
- [ ] `9.3.5` `consumeData`: checks.
- [ ] `9.3.6` `consumeData`: flow with the handler and registration.
- [ ] `9.3.7` Connect the `@close` of both types to the transport.
- [ ] `9.3.8` Notify `DataProducer` and `DataConsumer` when the transport closes.
- [ ] `9.3.9` `observer` with `newdataproducer` and `newdataconsumer`.

### 9.4 Handler and SDP

Origin: `aiortc_handler.py`, `media_section.py`, `remote_sdp.py`

- [ ] `9.4.1` `sendDataChannel`: assign the `streamId` with a counter modulo `MIS`. ⚠️ do not copy: the original ignores the `streamId` it receives.
- [ ] `9.4.2` `sendDataChannel`: create the `negotiated` channel. ⚠️ do not copy: the original does not pass `maxRetransmits`.
- [ ] `9.4.3` `sendDataChannel`: negotiate `m=application` the first time.
- [ ] `9.4.4` `receiveDataChannel`: create the `negotiated` channel.
- [ ] `9.4.5` `receiveDataChannel`: negotiate `m=application` the first time.
- [ ] `9.4.6` `AnswerMediaSection`: `application` section with `sctp-port`.
- [ ] `9.4.7` `AnswerMediaSection`: old `sctpmap`.
- [ ] `9.4.8` `OfferMediaSection`: `application` section with `sctp-port`.
- [ ] `9.4.9` `OfferMediaSection`: old `sctpmap` (`oldDataChannelSpec`).
- [ ] `9.4.10` `RemoteSdp.sendSctpAssociation`.
- [ ] `9.4.11` `RemoteSdp.receiveSctpAssociation`.
- [ ] `9.4.12` Implementation of `handler.DataChannel` on `RTCDataChannel`: copy each `RTCDataChannelBuffer` into a `DataChannelMessage`, translate `onStateChange` into `onOpen` and `onClose`, and compute `onBufferedAmountLow` with the threshold, which webrtc-java does not have.

### 9.5 Tests

- [ ] `9.5.1` The data channel part of `test_produce` and `test_consume`, with `FakeHandler`.
- [ ] `9.5.2` E2E test: send and receive text through the server.
- [ ] `9.5.3` E2E test: send and receive binary.
- [ ] `9.5.4` E2E test: unordered channel with `maxRetransmits`.
- [ ] `9.5.5` Test: `bufferedamountlow` fires.
- [ ] `9.5.6` Test: closing the transport closes the channels.
- [ ] `9.5.7` Publish version `0.3.0`.

**Phase 9 acceptance**

- Text and binary messages arrive end to end.
- The reliability parameters reach the native channel.

## Phase 10: stats and ICE

### 10.1 Statistics

Origin: `transport.py`, `producer.py`, `consumer.py`, `aiortc_handler.py`

- [ ] `10.1.1` `getStats` returns the `RTCStatsReport` of webrtc-java (`0.5.5`), and an empty or hand-built one with `FakeHandler`.
- [ ] `10.1.2` `handler.getTransportStats`.
- [ ] `10.1.3` `handler.getSenderStats`.
- [ ] `10.1.4` `handler.getReceiverStats`.
- [ ] `10.1.5` `Transport.getStats`.
- [ ] `10.1.6` `Producer.getStats`. ⚠️ do not copy: the original returns the list of `emit_for_results` and, with the transport closed, returns the exception instead of raising it.
- [ ] `10.1.7` `Consumer.getStats`, with the same care.
- [ ] `10.1.8` E2E tests of the three levels.

### 10.2 ICE

Origin: `transport.py`, `aiortc_handler.py`, `remote_sdp.py`

- [ ] `10.2.1` `Transport.restartIce`.
- [ ] `10.2.2` `handler.restartIce` on send, with `RTCOfferOptions.iceRestart`. ⚠️ do not copy: the original cannot ask for it because aiortc does not have that option.
- [ ] `10.2.3` `handler.restartIce` on recv.
- [ ] `10.2.4` `restartIce` before the transport is ready: it only updates the parameters.
- [ ] `10.2.5` `Transport.updateIceServers`.
- [ ] `10.2.6` `handler.updateIceServers` with `setConfiguration`. ⚠️ do not copy: in the original it only logs a warning.
- [ ] `10.2.7` `iceTransportPolicy` (`all` and `relay`).
- [ ] `10.2.8` `additionalSettings` and `proprietaryConstraints`: decide which ones make sense in webrtc-java and add them to `TransportOptions` with its builder, without fields reserved by jmediasoup. ⚠️ do not copy: the original calls `copy(deep=True)` on a `dict`.
- [ ] `10.2.9` `RemoteSdp.updateIceParameters`: remove `icelite` if it no longer applies.
- [ ] `10.2.10` E2E test: restart ICE and keep receiving media.
- [ ] `10.2.11` E2E test: connection through a TURN server, if the image or the environment allow it.
- [ ] `10.2.12` Publish version `0.4.0`.

**Phase 10 acceptance**

- `restartIce` restores the media in an active session.
- The three levels of `getStats` return real data.

## Phase 11: codecs and encodings

### 11.1 Codec options

Origin: `media_section.py`

- [ ] `11.1.0` Decide how `RemoteAnswerMediaSection` returns the changes that the Opus options make to the parameters of the offer (`sprop-stereo`, `useinbandfec`, `usedtx`, `ptime` and the NACK of `opusNack`): mediasoup-client modifies the object it receives, and here the models are immutable.
- [ ] `11.1.1` `opusStereo`.
- [ ] `11.1.2` `opusFec`.
- [ ] `11.1.3` `opusDtx`.
- [ ] `11.1.4` `opusMaxPlaybackRate`.
- [ ] `11.1.5` `opusMaxAverageBitrate`.
- [ ] `11.1.6` `opusPtime`.
- [ ] `11.1.7` `videoGoogleStartBitrate`.
- [ ] `11.1.8` `videoGoogleMaxBitrate`.
- [ ] `11.1.9` `videoGoogleMinBitrate`.
- [ ] `11.1.10` Check in the answer SDP that each option is applied.
- [ ] `11.1.11` `applyCodecParameters` with `sprop-stereo`.
- [ ] `11.1.12` `opusNack`, from mediasoup-client.
- [ ] `11.1.13` Remove the warning with which `send` ignores the codec options, and pass them to `RemoteSdp.send`.

### 11.2 Codec selection and encodings

Origin: `aiortc_handler.py`, `unified_plan_utils.py`

- [x] `11.2.1` `produce` with `codec`: send the chosen codec. Done in `6.2`; `SendTest` sends H264 by choosing it.
- [ ] `11.2.2` Assign the `rid`s `r0`, `r1`... to the encodings.
- [x] `11.2.3` A single encoding: merge it with the one obtained from the SDP. Done in `6.2`.
- [ ] `11.2.4` Several encodings: use them as they are.
- [x] `11.2.5` Pass `dtx`, `maxBitrate`, `maxFramerate`, `scaleResolutionDownBy`, `priority` and `networkPriority` to the native encoding. Done in `6.2`, except `dtx`, which the `RTCRtpEncodingParameters` of webrtc-java does not have; `priority` goes to its `bitratePriority`, as in the browsers.
- [ ] `11.2.6` E2E test per available codec: Opus, VP8, VP9 and H264. Receiving Opus, VP8 and H264 is already checked in `5.2.9` and `5.2.10`, and sending them in `6.2.10` and `6.2.11`.

### 11.3 Simulcast

Origin: `aiortc_handler.py`, `media_section.py`. aiortc does not support simulcast, so mediasoup-client (`Chrome111.ts`) has to be the reference.

- [ ] `11.3.1` Remove the `UnsupportedException` of `send` with more than one encoding, and port the `rid` and `L1Tn` that `Chrome111` sets on each one. `send` already passes a single encoding in `sendEncodings`.
- [ ] `11.3.2` `AnswerMediaSection`: `simulcast` and `rids`.
- [ ] `11.3.3` `AnswerMediaSection`: `simulcast_03` from the draft.
- [ ] `11.3.4` Add `S1T3` to each encoding with VP8 or H264. Review whether `numTemporalLayers` or `scalabilityMode` should be applied to the native encoding.
- [ ] `11.3.5` E2E test: the server receives several layers.
- [ ] `11.3.6` E2E test: the consumer receives different layers when the preferred layer changes on the server.

### 11.4 SVC

- [ ] `11.4.1` Detect VP9 SVC with one encoding and several spatial layers.
- [ ] `11.4.2` `addLegacySimulcast`. ⚠️ do not copy: it has several bugs (`.get` on a list, the `ssrc` key instead of `ssrcs`, `join` of integers).
- [ ] `11.4.3` The VP9 branch of `send`. ⚠️ do not copy: it assigns `sdp_transform.parse` without calling it.
- [ ] `11.4.4` Review in mediasoup-client whether this trick with a native `scalabilityMode` is still needed.
- [ ] `11.4.5` E2E test with VP9 SVC.

### 11.5 Live control

Origin: `producer.py`, `aiortc_handler.py`

- [ ] `11.5.1` `Producer.setMaxSpatialLayer`. ⚠️ do not copy: it uses `self._kind`, which does not exist.
- [ ] `11.5.2` `handler.setMaxSpatialLayer` with `getParameters` and `setParameters`. ⚠️ do not copy: in the original it only logs a warning.
- [ ] `11.5.3` `Producer.setRtpEncodingParameters`.
- [ ] `11.5.4` `handler.setRtpEncodingParameters`. ⚠️ do not copy: in the original it only logs a warning.
- [ ] `11.5.5` Connect `@setmaxspatiallayer` and `@setrtpencodingparameters` to the handler.
- [ ] `11.5.6` E2E test: when the layer is limited, the server stops receiving the higher ones.
- [ ] `11.5.7` Publish version `0.5.0`.

**Phase 11 acceptance**

- Simulcast works end to end with VP8.
- The codec options show in the SDP and, when possible, in the media.

## Phase 12: media utilities

### 12.1 Media utilities

Origin: `aiortc.contrib.media`, used in `examples/mediasoup.py`

- [ ] `12.1.1` `MediaBlackhole`: consume tracks and discard the data.
- [ ] `12.1.2` `MediaRecorder`: save audio as WAV.
- [ ] `12.1.3` `MediaRecorder`: save video. Decide the format and the dependency.
- [ ] `12.1.4` `MediaPlayer`: play audio from a file.
- [ ] `12.1.5` `MediaPlayer`: play video from a file. Decide the dependency.
- [ ] `12.1.6` Decide whether they go in a separate artifact so as not to add dependencies to the library.
- [ ] `12.1.7` Publish version `0.6.0`.

### 12.2 Port of the example

Dropped: the project does not include examples, and the README shows how to use the library. The port of `examples/mediasoup.py` that was planned here (an `examples` subproject with the WebSocket signaling of the demo) is not done.

**Phase 12 acceptance**

- The utilities play and record the media of a session with the server.

## Phase 13: release

### 13.1 Documentation

- [ ] `13.1.1` Javadoc for the whole public API.
- [ ] `13.1.2` Complete README: installation and usage of the whole API.
- [ ] `13.1.3` Guide to using the natives per platform, with the dependency for Gradle and for Maven (`0.5.7`).
- [ ] `13.1.4` Table of differences with pymediasoup and mediasoup-client.
- [ ] `13.1.5` Review that `LICENSE` and `NOTICE` are complete.
- [ ] `13.1.6` `CHANGELOG.md`.
- [ ] `13.1.7` `CONTRIBUTING.md`.

### 13.2 Multiplatform CI

- [ ] `13.2.1` Unit tests on Linux, macOS and Windows.
- [ ] `13.2.2` Loading the natives on every supported platform.
- [ ] `13.2.3` Integration tests on the platforms where Docker networking allows it.
- [ ] `13.2.4` Tests with a recent mediasoup version besides the 3.19.14 of the image (`0.5.14`), building the official demo or with another image.
- [ ] `13.2.5` Leak test: create and close transports in a loop, and watch the native memory.
- [ ] `13.2.6` Consider Gradle variants by OS and CPU so that the natives are picked by themselves (`0.5.7`).

### 13.3 Maven Central

Publishing to Central Portal is set up in `7.4`, with version `0.1.0`.

- [ ] `13.3.1` Check that the parity matrix is complete.
- [ ] `13.3.2` Publish version `1.0.0`.

**Phase 13 acceptance**

- A new project can use `io.github.hectorespert:jmediasoup` from Maven Central and complete the basic flow.

---

# Part C: beyond pymediasoup

## Phase 14: beyond pymediasoup (optional)

Features of mediasoup-client (`src/Transport.ts`) that pymediasoup does not have.

- [x] `14.1.1` Create consumers in batches in a single renegotiation (`createPendingConsumers`): moved to `4.5.5`, with closing in batches in `4.5.6`.
- [ ] `14.1.2` Pause consumers in batches (`pausePendingConsumers`).
- [ ] `14.1.3` Resume consumers in batches (`resumePendingConsumers`).
- [x] `14.1.4` `Transport.iceGatheringState`: moved to `4.3.4`, with the `icegatheringstatechange` and `icecandidateerror` events.
- [ ] `14.1.5` `Transport.getDataChannelMaxMessageSize`.
- [ ] `14.1.6` The `onRtpSender` and `onRtpReceiver` callbacks of `ProducerOptions` and `ConsumerOptions`: decide how they run with the transport queue.
- [ ] `14.1.7` Compare with mediasoup-client from time to time to spot new features.

---

# Parity matrix

It covers the pymediasoup API. `Phase` is the phase where it becomes functional; a basic property can appear earlier.

## Device

| Item | Phase |
|---|---|
| Constructor (`handlerFactory`) | 4.2 |
| `load` | 4.2 |
| `loaded`, `handlerName` | 4.2 |
| `rtpCapabilities`, `sctpCapabilities` (and `recvRtpCapabilities`, `sendRtpCapabilities`, from mediasoup-client) | 4.2 |
| `canProduce` | 4.2 |
| `createSendTransport`, `createRecvTransport` | 4.3 |
| `observer`: `newtransport` | 4.3 |

## Transport

| Item | Phase |
|---|---|
| `id`, `closed`, `direction`, `handler`, `connectionState`, `appData` | 4.3 |
| `close` | 4.3 |
| `produce` | 4.4 |
| `consume` | 4.5 |
| `produceData`, `consumeData` | 9.3 |
| `getStats` | 10.1 |
| `restartIce`, `updateIceServers` | 10.2 |
| `connect`, `connectionstatechange`, `produce` events | 4.3 |
| `producedata` event | 9.3 |
| `observer`: `close`, `newproducer`, `newconsumer` | 4.3, 4.4, 4.5 |
| `observer`: `newdataproducer`, `newdataconsumer` | 9.3 |
| RTP probator (`probatorConsumerCreated`) | 8.3 |

## Producer

| Item | Phase |
|---|---|
| `id`, `localId`, `closed`, `kind`, `rtpSender`, `track`, `rtpParameters`, `paused`, `maxSpatialLayer`, `appData` | 4.4 |
| `close`, `transportClosed` | 4.4 |
| `pause`, `resume` | 8.1 |
| `replaceTrack` | 8.2 |
| `getStats` | 10.1 |
| `setMaxSpatialLayer`, `setRtpEncodingParameters` | 11.5 |
| `stopTracks`, `disableTrackOnPause`, `zeroRtpOnPause` options | 8.1, 8.2 |
| `transportclose`, `trackended` events | 4.4, 8.2 |
| `observer`: `close`, `pause`, `resume`, `trackended` | 4.4, 8.1, 8.2 |
| Internal `@close`, `@replacetrack`, `@setmaxspatiallayer`, `@setrtpencodingparameters`, `@getstats` | 4.4, 8.2, 11.5, 10.1 |

## Consumer

| Item | Phase |
|---|---|
| `id`, `localId`, `producerId`, `closed`, `kind`, `rtpReceiver`, `track`, `rtpParameters`, `paused`, `appData` | 4.5 |
| `close`, `transportClosed` | 4.5 |
| `pause`, `resume` | 8.1 |
| `getStats` | 10.1 |
| `transportclose`, `trackended` events | 4.5, 8.2 |
| `observer`: `close`, `pause`, `resume`, `trackended` | 4.5, 8.1, 8.2 |
| Internal `@close`, `@getstats` | 4.5, 10.1 |

## DataProducer

| Item | Phase |
|---|---|
| `id`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol`, `appData` | 9.1 |
| `bufferedAmount`, `bufferedAmountLowThreshold` | 9.1 |
| `send` | 9.1 |
| `close`, `transportClosed` | 9.1 |
| `open`, `error`, `bufferedamountlow`, `transportclose` events | 9.1 |
| `observer`: `close` | 9.1 |

## DataConsumer

| Item | Phase |
|---|---|
| `id`, `dataProducerId`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol`, `appData` | 9.2 |
| `binaryType` | 9.2 |
| `close`, `transportClosed` | 9.2 |
| `open`, `error`, `message`, `transportclose` events | 9.2 |
| `observer`: `close` | 9.2 |

## HandlerInterface (Handler) and AiortcHandler (WebRtcJavaHandler)

| Item | Phase |
|---|---|
| `name`, `createFactory`, `close` | 5.1 |
| `pc`, `remoteSdp` (internal accessors) | 5.1 |
| `getNativeRtpCapabilities`, `getNativeSctpCapabilities` | 5.1 |
| `run` (the handler constructor in jmediasoup) | 4.1, 5.1 |
| `_setupTransport`, `_assertSendDirection`, `_assertRecvDirection` | 5.1 |
| `receive`, `stopReceiving` | 5.2 |
| `send`, `stopSending` | 6.2 |
| `replaceTrack` | 8.2 |
| `sendDataChannel`, `receiveDataChannel` | 9.4 |
| `getTransportStats`, `getSenderStats`, `getReceiverStats` | 10.1 |
| `restartIce`, `updateIceServers` | 10.2 |
| `setMaxSpatialLayer`, `setRtpEncodingParameters` | 11.5 |
| `@connect`, `@connectionstatechange` events | 4.1 |

## ortc.py

| Item | Phase |
|---|---|
| `RTP_PROBATOR_MID`, `RTP_PROBATOR_SSRC`, `RTP_PROBATOR_CODEC_PAYLOAD_TYPE` | 2.3 |
| `matchCodecs`, `isRtxCodec`, `reduceRtcpFeedback`, `matchHeaderExtensions` | 2.3 |
| `getExtendedRtpCapabilities`, `getRecvRtpCapabilities`, `canSend`, `canReceive` (and `getSendRtpCapabilities`, from mediasoup-client) | 2.4 |
| `getSendingRtpParameters`, `getSendingRemoteRtpParameters`, `reduceCodecs`, `generateProbatorRtpParameters` | 2.5 |

## handlers/sdp

| Item | Phase |
|---|---|
| `extractRtpCapabilities`, `extractDtlsParameters`, `getCname` | 3.3 |
| `applyCodecParameters` | 5.2, 11.1 |
| `getRtpEncodings` | 3.3 |
| `addLegacySimulcast` | 11.4 |
| `getCodecName`, `MediaSection` (`mid`, `closed`, `getDict`, `setIceParameters`, `disable`, `close`, `setDtlsRole`) | 3.4 |
| `AnswerMediaSection`, `OfferMediaSection`: audio and video | 3.4 |
| `AnswerMediaSection`, `OfferMediaSection`: `application` | 9.4 |
| `AnswerMediaSection`: `simulcast`, `simulcast_03`, `rids` | 11.3 |
| `AnswerMediaSection`: `codecOptions` | 11.1 |
| `RemoteSdp`: `updateIceParameters`, `updateDtlsRole`, `getNextMediaSectionIdx`, `send`, `receive`, `disableMediaSection`, `closeMediaSection`, `getSdp` | 3.5 |
| `RemoteSdp`: `_addMediaSection`, `_replaceMediaSection`, `_regenerateBundleMids`, `MediaSectionIdx` | 3.5 |
| `RemoteSdp`: `sendSctpAssociation`, `receiveSctpAssociation` | 9.4 |
| `planBReceive`, `planBStopReceiving` | Excluded (Plan B) |

## Models and utilities

| Item | Phase |
|---|---|
| `Error`, `UnsupportedError`, `InvalidStateError` (`MediasoupClientException`, `UnsupportedException`, `InvalidStateException`) | 1.1 |
| `EnhancedEventEmitter`, `emit_for_results` | 1.2 |
| `MediaKind`, `RtcpFeedback`, `Codec`, `RtpCodec`, `ExtendedCodec`, `RtpCodecCapability`, `RtpCodecParameters` | 1.3 |
| `RtpHeaderExtension`, `ExtendedHeaderExtension`, `RtpCapabilities`, `RTX`, `RtpEncodingParameters` | 1.3 |
| `RtpHeaderExtensionParameters`, `RtcpParameters`, `RtpParameters`, `ExtendedRtpCapabilities` | 1.3 |
| `NumSctpStreams`, `SctpCapabilities`, `SctpParameters`, `SctpStreamParameters` | 1.4 |
| `IceParameters`, `IceCandidate`, `DtlsFingerprint`, `DtlsRole`, `DtlsParameters`, `ConnectionState` | 1.5 |
| `IpVersion`, `PlainRtpParameters`, `TransportOptions` | 1.5 |
| `InternalTransportOptions` | 4.3 |
| The `RTCIceServer` of aiortc (`IceServer`) | 1.5 |
| `ProducerCodecOptions`, `ProducerOptions`, `ConsumerOptions`, `DataProducerOptions`, `DataConsumerOptions` | 1.6 |
| `MediaStreamTrackKind` | 1.3 (`MediaKind`) |
| `HandlerRunOptions` (`HandlerOptions`), `HandlerSendOptions`, `HandlerSendResult`, `HandlerReceiveOptions`, `HandlerReceiveResult` | 4.1 |
| `HandlerSendDataChannelResult`, `HandlerReceiveDataChannelOptions`, `HandlerReceiveDataChannelResult` | 4.1 |
| `ScalabilityMode`, `parse` (internal) | 2.2 |

## pymediasoup dependencies and their equivalent

| Dependency | Equivalent | Phase |
|---|---|---|
| `aiortc` | `webrtc-java` | 0.3, 5, 6 |
| `pydantic` | `record`s with validation and Jackson annotations | 1 |
| `pyee` | Typed listeners | 1.2 |
| `sdp-transform` | Our own port, internal | 3.1, 3.2 |
| `h264-profile-level-id` | Our own port, internal | 2.1 |
