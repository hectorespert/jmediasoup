# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Implement a Java client for [mediasoup](https://mediasoup.org), the counterpart of what [pymediasoup](https://github.com/Huddle01/pymediasoup) is for Python. pymediasoup models its API on the official JavaScript client (`mediasoup-client`) and passes an aiortc-based handler into its `Device`, so use both projects as the reference for API shape and behaviour.

## Roadmap

[TODO.md](TODO.md) holds the plan to reach an MVP and then parity with pymediasoup. It is a living document: phases, tasks and steps can change as the implementation progresses, so update it instead of following it to the letter when something turns out to be wrong.

## Commits

Credit Claude with an `Assisted-by:` trailer instead of `Co-Authored-By:`, naming the model that did the work:

```
Assisted-by: Claude Opus 5.5 <noreply@anthropic.com>
```

## Project state

Bare skeleton: the root package `io.github.hectorespert.jmediasoup` holds only `package-info.java` and a smoke test, so there is no API yet. The Java WebRTC stack has not been chosen yet.

## Commands

Use the Gradle wrapper (Gradle 9.1.0). There is one subproject, `jmediasoup`.

```bash
./gradlew build                  # compile, test and assemble the jar
./gradlew test                   # run all tests
./gradlew :jmediasoup:test --tests 'io.github.hectorespert.jmediasoup.SmokeTest.testsRunInTheRootPackage'   # run a single test
./gradlew clean
./gradlew publishToMavenLocal      # publish io.github.hectorespert:jmediasoup to ~/.m2
```

No linter or formatter is configured. Test reports are written to `jmediasoup/build/reports/tests/test/`.

## Build setup

- Java 21 through a Gradle toolchain (`jmediasoup/build.gradle.kts`). The foojay resolver plugin downloads a matching JDK if none is installed.
- Tests use JUnit 5 (Jupiter).
- Declare dependency versions in the version catalog, `gradle/libs.versions.toml`, and reference them as `libs.*` from the build script.
- The `java-library` plugin is applied, so `api` dependencies are exposed to consumers and `implementation` dependencies are not.
- The configuration cache is on (`gradle.properties`), so custom tasks and build logic have to be compatible with it.
