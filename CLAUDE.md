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

Phases 1 (foundations), 2 (ORTC) and 3 (SDP) are done, except for checking the SDP against webrtc-java, which needs the natives (task 5.1.15). `Device` loads and reports its capabilities, but there is no `Transport` yet. The module `io.github.hectorespert.jmediasoup` exports these packages, all `@NullMarked`:

- the root package: `Device`, the exceptions (`MediasoupClientException`, a checked base, with `UnsupportedException` and `InvalidStateException`), `Subscription`, `DataChannelMessage`, and the producer and consumer options;
- `rtp`, `sctp` and `transport`: the models, as validated, immutable records;
- `handler`: the contract with the WebRTC stack for custom handlers, the one of mediasoup-client 3.26: `HandlerFactory` (native capabilities by direction, `create`), `Handler`, which reports to its transport through the `HandlerListener` in its `HandlerOptions`, their options and results, and `DataChannel`.

Internal packages go under `io.github.hectorespert.jmediasoup.internal` and are not exported. `internal.Checks` validates the model constructors, `internal.ParameterValues` compares codec parameters, `internal.h264` is the port of h264-profile-level-id, `internal.ortc` is the port of `ortc.py` (following mediasoup-client where they differ), `internal.sdptransform` is the port of sdp-transform, whose parsed SDP is a tree of mutable maps and lists, `internal.sdp` holds the SDP handling of mediasoup-client on top of it, and `internal.event` delivers events to listeners, calls the user's asynchronous handlers (`connect`, `produce`) and serializes the work of a transport: `SerialExecutor` runs the user's code one task at a time on virtual threads, and `OperationQueue` chains the operations like the `AwaitQueue` of mediasoup-client. Use it for every event. The Javadoc task leaves the internal packages out, patching their compiled classes into the module so that the public sources still resolve.

Models that travel as JSON carry Jackson annotations and no runtime JSON code: `@JsonInclude(NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown = true)` on each record, `@JsonProperty(required = true)` on required primitive components, and `@JsonValue`/`@JsonCreator` on the `value()`/`fromValue()` of each enum. Test them with both Jackson 2 and 3 through `json.WithEachJackson`.

Tests that need a track, a data channel or a handler use `fake.FakeMediaStreamTrack`, `fake.FakeDataChannel` and `fake.FakeHandler` (the port of mediasoup-client's), test-only implementations that work without the webrtc-java natives. `fake.FakeParameters` returns what a mediasoup server would send, loaded from the JSON files in `src/test/resources/fixtures/`.

The WebRTC stack is [webrtc-java](https://github.com/devopvoid/webrtc-java); the results of the spike that validated it, and what they mean for the design, are in section 0.3 of TODO.md.

The design decisions (async model, threading, events, JSON, nullness, logging, JPMS, SDP representation, dependency scopes) are recorded in the "Decisiones" section of TODO.md. Follow them, and ask before departing from one.

## Commands

Use the Gradle wrapper (Gradle 9.8.2). There is one subproject, `jmediasoup`.

```bash
./gradlew build                  # compile, test and assemble the jar
./gradlew test                   # run all tests
./gradlew :jmediasoup:test --tests 'io.github.hectorespert.jmediasoup.SmokeTest.testsRunInTheRootPackage'   # run a single test
./gradlew clean
./gradlew publishToMavenLocal      # publish io.github.hectorespert:jmediasoup to ~/.m2
./gradlew spotlessApply          # format all files; `build` fails through spotlessCheck if any is not formatted
```

Spotless formats Java with palantir-java-format and adds the SPDX licence header, formats the `.gradle.kts` scripts with ktlint, and trims trailing whitespace in Markdown, YAML and TOML. Run `spotlessApply` after editing. Test reports are written to `jmediasoup/build/reports/tests/test/`.

## Build setup

- Java 21 through a Gradle toolchain (`jmediasoup/build.gradle.kts`). The foojay resolver plugin downloads a matching JDK if none is installed.
- Tests use JUnit Jupiter 6. The `org.gradlex.java-module-testing` plugin runs them inside the library module (whitebox, `--patch-module`), so they can use the internal packages; a module a test needs has to be added to `requires` in the `javaModuleTesting.whitebox` block.
- Declare dependency versions in the version catalog, `gradle/libs.versions.toml`, and reference them as `libs.*` from the build script.
- The `java-library` plugin is applied, so `api` dependencies are exposed to consumers and `implementation` dependencies are not.
- The configuration cache is on (`gradle.properties`), so custom tasks and build logic have to be compatible with it.
- `compileJava` records the project version in the module descriptor, where `RemoteSdp` reads it.
