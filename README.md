# jmediasoup

Java client for [mediasoup](https://mediasoup.org), the counterpart of what [pymediasoup](https://github.com/Huddle01/pymediasoup) is for Python. Its API follows the official JavaScript client, [mediasoup-client](https://mediasoup.org/documentation/v3/mediasoup-client/api/), and it uses [webrtc-java](https://github.com/devopvoid/webrtc-java) as the WebRTC stack.

> **Status:** `0.2.0` is on Maven Central. It sends and receives audio, video and data; see [Limitations](#limitations) for what is not supported yet.

## Installation

jmediasoup needs Java 21 or later. Besides jmediasoup, add the webrtc-java natives for each platform the application runs on (see [Platforms](#platforms)).

Gradle:

```kotlin
dependencies {
    implementation("io.github.hectorespert:jmediasoup:0.2.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:linux-x86_64")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.hectorespert</groupId>
    <artifactId>jmediasoup</artifactId>
    <version>0.2.0</version>
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

The application then pushes frames to the source with `videoSource.pushFrame(frame)`, and audio to a `CustomAudioSource` with `pushAudio`. `ProducerOptions` also takes the codec to send, among those of `device.sendRtpCapabilities()`, a single encoding with its `maxBitrate` and the rest of the RTP encoding parameters, and the codec options, such as Opus in stereo or the bitrate with which video starts:

```java
Producer audioProducer = sendTransport.produce(ProducerOptions.builder(audioTrack)
                .codecOptions(ProducerCodecOptions.builder().opusStereo(true).opusDtx(true).build())
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

## Platforms

webrtc-java publishes natives for these platforms, as classifiers of `dev.onvoid.webrtc:webrtc-java`. The CI of jmediasoup runs its tests, against a mediasoup server, only on Linux x86_64 so far.

| Classifier | Status |
|---|---|
| `linux-x86_64` | Tested in CI |
| `linux-aarch64` | Natives available, not tested |
| `linux-aarch32` | Natives available, not tested |
| `macos-x86_64` | Natives available, not tested |
| `macos-aarch64` | Natives available, not tested |
| `windows-x86_64` | Natives available, not tested |
| `windows-aarch64` | Natives available, not tested |

## Limitations

`0.2.0` covers the basic flow: load the device, send and receive audio and video, pause and resume, replace the track being sent, send and receive data, get stats, restart ICE, and close. This is not supported yet:

- Simulcast and SVC: `produce` with more than one encoding fails with `UnsupportedException`.

[TODO.md](TODO.md) holds the plan up to parity with pymediasoup and mediasoup-client.

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for the licences of the projects this library is ported from.
