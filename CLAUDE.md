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

Bare skeleton: the module `io.github.hectorespert.jmediasoup` has a `module-info.java` and the root package holds only `package-info.java` (`@NullMarked`) and a smoke test, so there is no API yet. Internal packages go under `io.github.hectorespert.jmediasoup.internal` and are not exported. The WebRTC stack is [webrtc-java](https://github.com/devopvoid/webrtc-java); the results of the spike that validated it, and what they mean for the design, are in section 0.3 of TODO.md.

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
