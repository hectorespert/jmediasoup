# Contributing

Thanks for helping with jmediasoup. Bug reports, questions and pull requests are welcome in [GitHub](https://github.com/hectorespert/jmediasoup). For a larger change, open an issue first, so that we can agree on it before the work. [TODO.md](TODO.md) holds the roadmap and the design decisions of the project.

## Building

You need a JDK to run Gradle; the build downloads Java 21 if it is not installed. The integration tests also need Docker, or podman with its socket.

```bash
./gradlew build                 # compile, run every test, check the format and build the jars
./gradlew test                  # unit tests
./gradlew :jmediasoup:test --tests 'io.github.hectorespert.jmediasoup.DeviceTest'   # one test class
./gradlew integrationTest       # tests against a mediasoup server in a container
./gradlew spotlessApply         # format the code
```

The unit tests load the webrtc-java natives of your platform. The integration tests start the mediasoup demo and ffmpeg with Testcontainers, on the host network, so they only run on Linux x86_64; elsewhere, or without Docker, they are skipped. With podman, start its socket and point `DOCKER_HOST` at it:

```bash
systemctl --user start podman.socket
DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock ./gradlew integrationTest
```

The reports of the tests are in `jmediasoup/build/reports/tests/`.

## Code

- The API follows [mediasoup-client](https://github.com/versatica/mediasoup-client) 3.26, and the algorithm its `Chrome111` handler, also where [pymediasoup](https://github.com/Huddle01/pymediasoup) differs. Keep the names of the original and its comments, so that both can be compared.
- `./gradlew spotlessApply` formats Java with palantir-java-format, adds the licence header to new files, and formats the Gradle scripts. `build` fails if a file is not formatted.
- The public API is `@NullMarked`, and any Javadoc warning fails the build, so every public element needs its Javadoc. Code that is not public API goes in the `internal` packages, which the module does not export.
- Each change comes with its tests. The unit tests run inside the module, so they can use the internal packages; `fake.FakeHandler` works without the natives.

## Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`, with the summary in the imperative and lowercase, such as `fix(sdp): keep the rid of the encodings`. The types are `feat`, `fix`, `test`, `docs`, `build`, `ci`, `refactor` and `chore`, and a breaking change has `!` and a `BREAKING CHANGE:` footer.

## Licence

jmediasoup is under the [Apache License 2.0](LICENSE), and so are the contributions to it.
