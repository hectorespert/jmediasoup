# jmediasoup

Java client for [mediasoup](https://mediasoup.org), the counterpart of what [pymediasoup](https://github.com/Huddle01/pymediasoup) is for Python. Its API follows the official JavaScript client, [mediasoup-client](https://mediasoup.org/documentation/v3/mediasoup-client/api/), and it uses [webrtc-java](https://github.com/devopvoid/webrtc-java) as the WebRTC stack.

> **Status:** `0.4.0` is on Maven Central. It sends and receives audio, video and data, and sends video in simulcast and SVC; see [Limitations](#limitations) for what is not supported yet.

## Installation

jmediasoup needs Java 21 or later. Besides jmediasoup, add the webrtc-java natives for each platform the application runs on (see [Platforms](#platforms)).

Gradle:

```kotlin
dependencies {
    implementation("io.github.hectorespert:jmediasoup:0.4.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:linux-x86_64")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.hectorespert</groupId>
    <artifactId>jmediasoup</artifactId>
    <version>0.4.0</version>
</dependency>
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>0.20.0</version>
    <classifier>linux-x86_64</classifier>
    <scope>runtime</scope>
</dependency>
```

jmediasoup logs through [SLF4J](https://www.slf4j.org); add a binding to see its logs. It is a JPMS module, `io.github.hectorespert.jmediasoup`, and also works on the classpath. On the module path, nothing requires the natives, an automatic module named after the classifier, so add it to the module graph when launching the application, for example with `--add-modules webrtc.linux.x86_64`.

## Usage

mediasoup does not define how a client talks to the server, so each application brings its own signaling. In the examples below, `signaling` stands for it: it sends a request to the application server and returns a `CompletableFuture` with the response, already converted to the jmediasoup models. The models carry Jackson annotations, so Jackson 2 or 3 can read them from the JSON of mediasoup.

### Load the device

A `Device` represents the endpoint that connects to a mediasoup router. Load it with the RTP capabilities of the router:

```java
WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory();
Device device = new Device(factory);

RtpCapabilities routerRtpCapabilities = signaling.request("getRouterRtpCapabilities").join();
device.load(routerRtpCapabilities).join();
```

`WebRtcJavaHandler.createFactory()` creates its own webrtc-java `PeerConnectionFactory`, without audio devices. To use one of the application, pass it to `createFactory(peerConnectionFactory)`. The tracks to send must come from the same `PeerConnectionFactory`.

### Send

Create a send transport with the parameters of a WebRTC transport created on the server. The transport calls the `connect` handler the first time it needs to connect, and the `produce` handler each time it sends a new track, so that the application creates the producer on the server and returns its id:

```java
TransportOptions sendOptions = signaling.request("createWebRtcTransport", "send").join();

SendTransport sendTransport = device.createSendTransport(
        sendOptions,
        dtlsParameters -> signaling.request("connectWebRtcTransport", sendOptions.id(), dtlsParameters),
        request -> signaling.request("produce", sendOptions.id(), request.kind(), request.rtpParameters()));

PeerConnectionFactory peerConnectionFactory = factory.peerConnectionFactory();
CustomVideoSource videoSource = new CustomVideoSource();
VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("video", videoSource);

Producer producer = sendTransport.produce(ProducerOptions.builder(videoTrack).build()).join();
```

The application then pushes frames to the source with `videoSource.pushFrame(frame)`, and audio to a `CustomAudioSource` with `pushAudio`. `ProducerOptions` also takes the codec to send, among those of `device.sendRtpCapabilities()`, the encodings with their `maxBitrate` and the rest of the RTP encoding parameters, and the codec options, such as Opus in stereo or the bitrate with which video starts:

```java
Producer audioProducer = sendTransport.produce(ProducerOptions.builder(audioTrack)
                .codecOptions(ProducerCodecOptions.builder().opusStereo(true).opusDtx(true).build())
                .build())
        .join();
```

Several encodings send the track in simulcast, one stream per encoding, from which mediasoup picks the one that each consumer receives. libwebrtc limits the number of streams by resolution: three need at least 960x540.

```java
Producer videoProducer = sendTransport.produce(ProducerOptions.builder(videoTrack)
                .encodings(List.of(
                        RtpEncodingParameters.builder().scaleResolutionDownBy(4.0).maxBitrate(150_000).build(),
                        RtpEncodingParameters.builder().scaleResolutionDownBy(2.0).maxBitrate(500_000).build(),
                        RtpEncodingParameters.builder().maxBitrate(1_500_000).build()))
                .build())
        .join();
```

While it sends, `videoProducer.setMaxSpatialLayer(1)` stops the streams above the second one, and `setRtpEncodingParameters` changes every encoding, with the fields of a webrtc-java `RTCRtpEncodingParameters` that are not `null`.

With VP9, a single encoding with a scalability mode of several spatial layers sends them in a single stream (SVC), from which mediasoup drops the layers that each consumer does not receive. On Linux and Windows, it needs a router whose VP9 codec has no `x-google-*` parameters (see [Limitations](#limitations)):

```java
RtpCodecCapability vp9 = device.sendRtpCapabilities().codecs().stream()
        .filter(codec -> codec.mimeType().equalsIgnoreCase("video/VP9"))
        .findFirst()
        .orElseThrow();

Producer svcProducer = sendTransport.produce(ProducerOptions.builder(videoTrack)
                .codec(vp9)
                .encodings(List.of(RtpEncodingParameters.builder().scalabilityMode("L3T3").build()))
                .build())
        .join();
```

### Receive

Create a receive transport, and consume each producer that the server announces with the parameters of the consumer it created:

```java
TransportOptions recvOptions = signaling.request("createWebRtcTransport", "recv").join();

RecvTransport recvTransport = device.createRecvTransport(
        recvOptions,
        dtlsParameters -> signaling.request("connectWebRtcTransport", recvOptions.id(), dtlsParameters));

ConsumerOptions consumerOptions = signaling.request("consume", recvOptions.id(), device.recvRtpCapabilities()).join();
Consumer consumer = recvTransport.consume(consumerOptions).join();

if (consumer.track() instanceof VideoTrack track) {
    track.addSink(frame -> System.out.println(frame.buffer.getWidth() + "x" + frame.buffer.getHeight()));
}
```

Audio tracks take an `AudioTrackSink` in the same way.

### Send and receive data

Data channels need a server transport with SCTP, which mediasoup enables when the client passes `device.sctpCapabilities()` to create it. To produce data, give the send transport a fourth handler, which creates the data producer on the server and returns its id:

```java
SendTransport sendTransport = device.createSendTransport(
        sendOptions,
        dtlsParameters -> signaling.request("connectWebRtcTransport", sendOptions.id(), dtlsParameters),
        request -> signaling.request("produce", sendOptions.id(), request.kind(), request.rtpParameters()),
        request -> signaling.request("produceData", sendOptions.id(), request.sctpStreamParameters(), request.label()));

DataProducer dataProducer = sendTransport.produceData(DataProducerOptions.builder().label("chat").build()).join();
dataProducer.onOpen(() -> {
    try {
        dataProducer.send("hello");
    } catch (MediasoupClientException e) {
        // The channel closed meanwhile.
    }
});
```

`DataProducerOptions` makes the channel unordered with `ordered(false)`, `maxPacketLifeTime` or `maxRetransmits`. To receive, consume the data consumer that the server created:

```java
DataConsumerOptions dataConsumerOptions = signaling.request("consumeData", recvOptions.id(), dataProducerId).join();
DataConsumer dataConsumer = recvTransport.consumeData(dataConsumerOptions).join();
dataConsumer.onMessage(message -> System.out.println(message.isBinary() ? message.size() + " bytes" : message.asText()));
```

### Replace the track, stats and ICE

A producer replaces the track it sends without renegotiating, and producers, consumers and transports give the stats of webrtc-java:

```java
producer.replaceTrack(otherVideoTrack).join();

RTCStatsReport producerStats = producer.getStats().join();
RTCStatsReport transportStats = sendTransport.getStats().join();
```

To use STUN or TURN servers, or only relayed candidates, add them to the options of the transport before creating it, and change them later with `updateIceServers`. After the server restarts ICE on its transport, restart it on the client with the new ICE parameters:

```java
TransportOptions relayed = sendOptions.toBuilder()
        .iceServers(List.of(new IceServer(List.of("turn:turn.example.com:3478"), "user", "secret")))
        .iceTransportPolicy(IceTransportPolicy.RELAY)
        .build();

IceParameters iceParameters = signaling.request("restartIce", sendOptions.id()).join();
sendTransport.restartIce(iceParameters).join();
```

### Events and closing

Transports report their connection state, and producers and consumers report when their transport closes. Listeners run one at a time on an executor of each transport, never on the threads of webrtc-java:

```java
sendTransport.onConnectionStateChange(state -> System.out.println("send transport " + state));
consumer.onTransportClose(() -> System.out.println("consumer closed with its transport"));
```

`pause()` and `resume()` on a producer or a consumer stop and restart the media: they disable and enable the track, and with `zeroRtpOnPause` a producer sends no RTP at all while paused. Closing a producer or a consumer stops sending or receiving it. Tell the server too, so that it pauses, resumes or closes its side. Closing a transport closes its producers and consumers, and its data producers and consumers. jmediasoup never releases the tracks that the application created, whatever `stopTracks` says, since webrtc-java tracks cannot be stopped: call `dispose()` on them once no producer sends them. Close the factory last: it disposes the `PeerConnectionFactory` if it created it:

```java
producer.close();
sendTransport.close();
recvTransport.close();
factory.close();
```

The futures fail with a `MediasoupClientException`, wrapped in the `CompletionException` of `join()`: an `InvalidStateException` when the object is closed or not ready, such as a device not loaded, and an `UnsupportedException` when the device or the transport cannot do it, such as producing a kind that the router does not have. Invalid arguments throw `IllegalArgumentException` or `NullPointerException` at once. Each object also has an `observer()`, whose events, such as `onNewProducer` of a transport or `onClose` of a consumer, are meant for monitoring.

## Platforms

webrtc-java publishes natives for these platforms, as classifiers of `dev.onvoid.webrtc:webrtc-java`. The CI of jmediasoup runs its unit tests, which load the natives and send media between peer connections, on each platform that GitHub has runners for, and its tests against a mediasoup server on Linux x86_64, the only one where the mediasoup image and the host network of Docker are available.

| Classifier | Status |
|---|---|
| `linux-x86_64` | Tested in CI, also against a mediasoup server |
| `linux-aarch64` | Tested in CI |
| `linux-aarch32` | Natives available, not tested |
| `macos-x86_64` | Tested in CI |
| `macos-aarch64` | Tested in CI |
| `windows-x86_64` | Tested in CI |
| `windows-aarch64` | Tested in CI |

The application needs the natives of each platform it runs on, with the version of webrtc-java that jmediasoup uses. A distribution for several platforms adds one dependency per classifier:

```kotlin
dependencies {
    implementation("io.github.hectorespert:jmediasoup:0.4.0")
    for (classifier in listOf("linux-x86_64", "macos-aarch64", "windows-x86_64")) {
        runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:$classifier")
    }
}
```

To build only for the platform of the build, Gradle can pick the classifier from the system properties:

```kotlin
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val webrtcClassifier = buildString {
    append(when {
        os.startsWith("linux") -> "linux"
        os.startsWith("mac") -> "macos"
        else -> "windows"
    })
    append(when (arch) {
        "amd64", "x86_64" -> "-x86_64"
        "arm" -> "-aarch32"
        else -> "-aarch64"
    })
}

dependencies {
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:$webrtcClassifier")
}
```

Maven does the same with a profile per platform, activated by the operating system, that sets the classifier:

```xml
<profiles>
    <profile>
        <id>linux-x86_64</id>
        <activation><os><name>Linux</name><arch>amd64</arch></os></activation>
        <properties><webrtc.classifier>linux-x86_64</webrtc.classifier></properties>
    </profile>
    <profile>
        <id>macos-aarch64</id>
        <activation><os><family>mac</family><arch>aarch64</arch></os></activation>
        <properties><webrtc.classifier>macos-aarch64</webrtc.classifier></properties>
    </profile>
    <profile>
        <id>windows-x86_64</id>
        <activation><os><family>windows</family><arch>amd64</arch></os></activation>
        <properties><webrtc.classifier>windows-x86_64</webrtc.classifier></properties>
    </profile>
</profiles>
```

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>0.20.0</version>
    <classifier>${webrtc.classifier}</classifier>
    <scope>runtime</scope>
</dependency>
```

The Java `os.arch` is `amd64` on Linux and Windows, and `x86_64` on macOS with Intel.

## Differences with mediasoup-client and pymediasoup

The classes, methods and options are those of mediasoup-client 3.26, and the algorithm follows its `Chrome111` handler. What changes is what Java does in another way:

| | mediasoup-client | pymediasoup | jmediasoup |
|---|---|---|---|
| Asynchronous calls | `Promise` | `async` | `CompletableFuture` |
| Events | `on("connect")`, `on("produce")` | `@transport.on("connect")` | The `connect`, `produce` and `producedata` handlers are passed to `createSendTransport` and `createRecvTransport`, and the other events have typed listeners, `onConnectionStateChange(...)`, that return a `Subscription` |
| Threads | The event loop | The asyncio loop | Listeners and handlers of the application run one at a time on an executor of each transport, never on the threads of webrtc-java |
| Models | Plain objects | pydantic models | Immutable records with builders, checked when built, with Jackson annotations |
| Errors | `InvalidStateError`, `UnsupportedError`, `TypeError` | The same | `InvalidStateException`, `UnsupportedException`, `IllegalArgumentException` |
| WebRTC stack | The browser, with a handler per browser | aiortc | webrtc-java, with `WebRtcJavaHandler`; other stacks can implement `HandlerFactory` |
| Tracks | `stopTracks` stops them | They are stopped | The application disposes them: webrtc-java tracks cannot be stopped |
| Data messages | `binaryType`, `ArrayBuffer` or `Blob` | `bytes` or `str` | `DataChannelMessage`, text or binary |
| Media | `getUserMedia` | `MediaPlayer`, `MediaRecorder` of aiortc | The sources and sinks of webrtc-java |

Unlike pymediasoup, whose aiortc does not support them, jmediasoup sends simulcast and SVC, pauses by disabling the track, stops sending when a producer closes, restarts ICE, updates the ICE servers, changes the layers and encodings of a producer, and creates, pauses, resumes and closes consumers in batches, as mediasoup-client does. It does not have, from mediasoup-client, `getDataChannelMaxMessageSize` nor the `onRtpSender` and `onRtpReceiver` callbacks of the options, nor the handlers of the other browsers.

## Limitations

`0.4.0` covers the basic flow: load the device, send and receive audio and video, pause and resume, replace the track being sent, send and receive data, get stats, restart ICE, and close; and it applies the codec options and sends video in simulcast and SVC.

On Linux and Windows, the video encoders of webrtc-java 0.20.0 drop the scalability mode of the encodings when the answer of mediasoup has an `x-google-*` parameter for the codec, such as the `x-google-start-bitrate` that many routers give their video codecs, or those that the `videoGoogle*` codec options add. Then they send neither SVC nor the temporal layers of simulcast: simulcast still works, with one temporal layer per stream. To send SVC, leave the `x-google-*` parameters out of the VP9 codec of the router. On macOS, whose encoders are others, the scalability mode is kept.

[TODO.md](TODO.md) holds the plan up to parity with pymediasoup and mediasoup-client.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) to build the project and run its tests, and [CHANGELOG.md](CHANGELOG.md) for the changes of each version.

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for the licences of the projects this library is ported from.
