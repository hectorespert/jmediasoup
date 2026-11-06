# TODO: hoja de ruta de jmediasoup

Objetivo: un cliente Java de [mediasoup](https://mediasoup.org) con las mismas funcionalidades que [pymediasoup](https://github.com/Huddle01/pymediasoup), usando [mediasoup-client](https://github.com/versatica/mediasoup-client) como referencia donde pymediasoup deja huecos.

> **Documento vivo.** Todas las fases, tareas y pasos pueden cambiar según se vaya implementando: se pueden añadir, dividir, reordenar o eliminar. Si la implementación demuestra que un paso está mal planteado, se corrige aquí en vez de seguirlo al pie de la letra.

## Cómo leer este documento

- Cada subtarea tiene un ID (`3.2.4`) y debería caber en un commit o PR pequeño.
- `Origen:` indica el código de referencia. Las rutas son de pymediasoup (commit `7e6b7ab`) salvo que se indique otra cosa.
- `⚠️ no copiar` marca un bug o un stub del original que no debe reproducirse.
- Cada fase termina con sus criterios de aceptación.
- La [matriz de paridad](#matriz-de-paridad) relaciona la API de pymediasoup con la fase que la cubre.

## Resumen

| Parte | Fase | Contenido |
|---|---|---|
| A: MVP | [0](#fase-0-proyecto-y-decisiones) | Proyecto y decisiones |
| A: MVP | [1](#fase-1-fundamentos) | Errores, eventos, modelos y JSON |
| A: MVP | [2](#fase-2-ortc) | Lógica ORTC y capacidades RTP |
| A: MVP | [3](#fase-3-sdp) | Parser y generador de SDP |
| A: MVP | [4](#fase-4-device-y-transport-con-fakehandler) | `Device` y `Transport` con `FakeHandler` |
| A: MVP | [5](#fase-5-webrtcjavahandler-recepción) | `WebRtcJavaHandler`: recepción |
| A: MVP | [6](#fase-6-webrtcjavahandler-envío) | `WebRtcJavaHandler`: envío |
| A: MVP | [7](#fase-7-hito-mvp) | Hito MVP con servidor real |
| B: paridad | [8](#fase-8-control-de-medios) | Control de medios |
| B: paridad | [9](#fase-9-data-channels) | Data channels |
| B: paridad | [10](#fase-10-stats-e-ice) | Stats e ICE |
| B: paridad | [11](#fase-11-codecs-y-encodings) | Codecs, simulcast y SVC |
| B: paridad | [12](#fase-12-ejemplos-y-utilidades-de-media) | Ejemplos y utilidades de media |
| B: paridad | [13](#fase-13-publicación) | Publicación |
| C: extras | [14](#fase-14-más-allá-de-pymediasoup-opcional) | Más allá de pymediasoup |

**Definición de MVP:** contra un servidor mediasoup real y con un test automatizado, `Device.load`, crear transportes send y recv, producir audio y vídeo desde una fuente custom (sin simulcast), consumir audio y vídeo con un sink, y cerrar limpiamente observando `connectionState`.

**Qué significa "paridad":** la misma API pública, pero funcional donde pymediasoup no lo es. Varias funciones del original son stubs por limitaciones de aiortc, y aquí deben funcionar de verdad.

**Exclusión deliberada:** Plan B. Existe en `media_section.py` y `remote_sdp.py`, pero ningún handler lo usa.

## Decisiones

**Ya tomadas**

- Coordenadas Maven: `io.github.hectorespert:jmediasoup`, con paquete raíz `io.github.hectorespert.jmediasoup`.
- Servidor de pruebas: la imagen de terceros `mmahdisonic/mediasoup-demo`, fijada por digest (sección 0.4). En local se ejecuta con podman.
- Licencia: Apache-2.0. El código a portar es MIT (pymediasoup, sdp-transform) e ISC (mediasoup-client, h264-profile-level-id), con sus avisos en `NOTICE`.
- Subproyecto: `jmediasoup`, que da nombre al artefacto sin configuración adicional.
- Stack WebRTC: `dev.onvoid.webrtc:webrtc-java` (Apache-2.0), confirmado por el spike de la sección 0.3. Validado en local en Linux x86_64; falta probarlo contra un servidor mediasoup real y en otras plataformas.

**Diseño** (sección 0.5)

- API asíncrona con `CompletableFuture`. Quien prefiera estilo bloqueante usa `.join()` en un virtual thread.
- Transportes (sección 4.3): dos tipos, `SendTransport` y `RecvTransport`, que heredan de una clase sellada `Transport`, como libmediasoupclient y mediasoup-client-android, en vez del `Transport` único de pymediasoup. Así `produce` solo existe en el de envío y `consume` en el de recepción. Se crean con los handlers sueltos: `createSendTransport(options, connect, produce)` y `createRecvTransport(options, connect)`. `producedata` se añadirá en la Fase 9 con una sobrecarga de cuatro parámetros. `ConnectHandler` devuelve un `CompletionStage<?>` cuyo valor se ignora, para que valga directamente el futuro de la señalización. `appData` es de solo lectura, como en pymediasoup (su setter lanza una excepción); un setter se podría añadir sin romper nada. Los eventos que provoca `close()` (observer `close`, `transportclose`) se entregan después, en el ejecutor del transporte, igual que el `newtransport` del `Device`.
- Hilos (sección 4.3): cada transporte tiene un ejecutor en serie (`internal.event.SerialExecutor`) sobre hilos virtuales compartidos, donde se ejecuta todo el código del usuario: listeners y handlers. Ninguno corre en un hilo nativo ni en el del que llama, ni dos a la vez, y pueden bloquear sin parar la factory; no heredan thread-locals (`MDC`). Las operaciones (`produce`, `consume`...) pasan además por una cola como la `AwaitQueue` de mediasoup-client (`internal.event.OperationQueue`), que las encadena de una en una y al cerrar rechaza las pendientes con `InvalidStateException`. Los eventos del handler no esperan a la cola: se entregan en cuanto llegan, en el ejecutor en serie, como en mediasoup-client. Clases propias en vez de Guava (`MoreExecutors.newSequentialExecutor`, `ExecutionSequencer`), que se quitó en `0.1.3` y no rechaza lo pendiente. Se usa `ReentrantLock` en vez de `synchronized` para no fijar hilos virtuales en Java 21–23.
- Eventos: `connect` y `produce` se pasan al crear el transporte y devuelven un `CompletableFuture`, así que no pueden faltar. El resto son listeners tipados (`onConnectionStateChange(...)`) que devuelven una suscripción para darse de baja.
- Listeners (sección 1.2): `Consumer<T>` para los eventos con valor y `Runnable` para los que no tienen. `onXxx` devuelve una `Subscription` (`AutoCloseable`, `close()` idempotente). En cuanto `close()` vuelve, el listener no recibe nada más, ni siquiera el evento que se está entregando; uno añadido durante una entrega empieza con el siguiente evento. Si un listener lanza una excepción, se registra a nivel ERROR con SLF4J y se sigue con los demás.
- Handlers asíncronos (sección 1.2): `connect` y `produce` son interfaces propias (`ConnectHandler`, `ProduceHandler` con un record de petición) que devuelven un `CompletionStage`. Si el handler lanza, devuelve `null` o su stage falla, la operación falla con `MediasoupClientException` y la causa original. jmediasoup no pone timeout; lo pone el usuario en su señalización.
- Tipos de texto cerrado como enums con su valor JSON.
- La API pública usa los tipos de webrtc-java (`MediaStreamTrack`, `RTCRtpSender`...), como pymediasoup usa los de aiortc. Solo el data channel tiene abstracción propia (`handler.DataChannel`, sección 1.7); sender y receiver se exponen como `@Nullable` y son `null` con `FakeHandler`, que usa una subclase de prueba de `MediaStreamTrack`. Se necesita el jar de webrtc-java, pero no sus nativos, salvo con `WebRtcJavaHandler`. Deja fuera Android.
- Handler (sección 4.1): contrato de mediasoup-client 3.26 en el paquete `handler`, con los nombres `Handler` y `HandlerFactory`. La factory da las capacidades nativas por dirección y crea handlers ya listos; el handler avisa al transporte por un `HandlerListener` que recibe en sus opciones, y tiene desde el principio todos los métodos de 3.26.
- Un único artefacto, `jmediasoup`, con el handler pluggable por interfaz. `FakeHandler` solo en tests, como en pymediasoup.
- Dependencias: webrtc-java, `jackson-annotations` y JSpecify como `api`; SLF4J como `implementation`; `jackson-databind` solo en tests. Los nativos de webrtc-java los declara el usuario, con instrucciones para Gradle y Maven.
- Nulos con JSpecify: `@NullMarked` en cada paquete y `@Nullable` en lo opcional.
- Modelos con `record` y anotaciones de Jackson. jmediasoup no necesita JSON en tiempo de ejecución: el usuario convierte con su propio `ObjectMapper`, de Jackson 2 o 3. Con Gson harían falta adapters (enums, `OS`/`MIS`, números dentro de `Map`); un `fromMap`/`toMap` propio se puede añadir más adelante sin romper nada.
- `appData` como `Map<String, Object>` inmutable que admite valores `null`.
- Logging con SLF4J 2.
- Java 21 como versión mínima. Podría bajar a 17 sin cambiar otras decisiones (webrtc-java es Java 8), pero se mantiene 21 por el `switch` con patrones, `SequencedMap` y los virtual threads.
- Modelos (sección 1.3) en subpaquetes exportados por área: `jmediasoup.rtp`, `jmediasoup.sctp` y `jmediasoup.transport`. Lo opcional es `null` y no se rellena con los valores por defecto de pydantic; las listas y mapas ausentes quedan vacíos e inmutables. El constructor valida lo obligatorio y los rangos y formatos de cada campo (payload type, SSRC de 32 bits sin signo, `mimeType`...); las reglas entre campos van en ORTC, salvo las contradicciones dentro de un mismo record que no dependen del contexto (fiabilidad de `SctpStreamParameters`). Los records que el usuario construye con 4 o más opcionales tienen builder escrito a mano.
- Opciones (sección 1.6): `ProducerOptions`, `ConsumerOptions`, `DataProducerOptions`, `DataConsumerOptions` y `ProducerCodecOptions` van en el paquete raíz, junto a las clases que las usan. Las que solo construye el usuario y no se serializan tienen sus flags como `boolean` con el valor por defecto del original en el builder. De mediasoup-client se añaden `opusNack` y `streamId`; `onRtpSender` y `onRtpReceiver` quedan para la Fase 14.
- Excepción a la 0.5.5 (sección 1.5): los servidores ICE son un record propio, `IceServer`, porque es configuración que suele llegar como JSON y `RTCIceServer` de webrtc-java es mutable. El handler lo convierte.
- JSON (sección 1.8): un valor de enum desconocido falla al leer, con un mensaje claro, en vez de leerse como `null`.
- ORTC (sección 2.3) y SDP (Fase 3): donde pymediasoup y mediasoup-client difieren, se sigue a mediasoup-client, que tiene la versión actual del algoritmo. La API sigue siendo la de pymediasoup.
- Capacidades (sección 2.4): modelo de mediasoup-client 3.26. El handler da capacidades nativas por dirección, `Device.load` calcula unas capacidades extendidas de recepción y otras de envío (con la opción `preferLocalCodecsOrder`), y `canSend` y `canReceive` trabajan sobre las capacidades de envío y de recepción, no sobre las extendidas.
- `parameters` de codec: `Map<String, Object>` que conserva el orden y el tipo JSON de cada valor (texto o número). ORTC los compara con `internal.ParameterValues`, que trata `100`, `100L` y `"100"` como iguales. `RtpCodec` es una interfaz sellada que implementan `RtpCodecCapability` y `RtpCodecParameters`.
- Excepciones (sección 1.1): base checked `MediasoupClientException extends Exception`, con `UnsupportedException` e `InvalidStateException` como subclases. Los métodos síncronos la declaran; en un `CompletableFuture` llega como causa del fallo (`CompletionException` en `join()`). Un argumento no válido lanza `IllegalArgumentException` y uno obligatorio que llega `null`, `NullPointerException`. Los `Exception` genéricos del original (SDP o transceiver inesperados) lanzan la base. Un codec elegido en `ProducerOptions.codec` que no se puede enviar lanza `UnsupportedException` (sección 2.5), aunque el original lanza `TypeError`.
- `module-info.java` desde el principio, exportando solo los paquetes públicos. Los paquetes internos van bajo `io.github.hectorespert.jmediasoup.internal` (`.internal.sdp`, `.internal.sdptransform`, `.internal.h264`...) y no se exportan.
- Los tests se ejecutan dentro del módulo de la librería (whitebox, con `--patch-module`) mediante el plugin `org.gradlex.java-module-testing`. Así ven los paquetes internos y comprueban a la vez los `requires` y `exports`.
- Ports propios de `sdp-transform` y `h264-profile-level-id` como paquetes internos (`internal.sdptransform` e `internal.h264`). El código de SDP de mediasoup-client (`handlers/sdp`) va en `internal.sdp`. No hay alternativa Java aprovechable (ver `0.5.13`). El SDP parseado se representa con `Map<String, Object>`, como el objeto de sdp-transform y el `dict` de pymediasoup.
- Versiones: SemVer. `0.1.0` con el MVP (`7.4`), una versión menor al cerrar cada fase de paridad (`0.2.0` la 8, `0.3.0` la 9, `0.4.0` la 10, `0.5.0` la 11 y `0.6.0` la 12) y `1.0.0` con la 13. Las correcciones entre fases salen como parches. En `0.x` una versión menor puede romper la API; desde la `1.0.0`, SemVer estricto. Solo se publica desde CI, al empujar una etiqueta `vX.Y.Z`.
- La versión la calcula desde git el plugin `gradle-git-versioning` (qoomon). Se ha elegido frente a axion-release, que está mejor mantenido pero tiene incidencias abiertas con el configuration cache, porque su versión 7.0 lo soporta. Es GPL-3.0, pero solo se usa en el build y no afecta a la licencia de la librería.
- Servidor: mediasoup v3. La CI valida la versión de la imagen (3.19.14), y en la Fase 13 se añade una reciente.

---

# Parte A: hasta el MVP

## Fase 0: proyecto y decisiones

### 0.1 Limpieza de la plantilla

- [x] `0.1.1` Borrar `org.example.Library` y `org.example.LibraryTest`.
- [x] `0.1.2` Quitar `commons-math3` y `guava` de `lib/build.gradle.kts`.
- [x] `0.1.3` Quitar `commons-math3` y `guava` de `gradle/libs.versions.toml`.
- [x] `0.1.4` Quitar los comentarios generados por `gradle init` de los scripts de build.
- [x] `0.1.5` Crear el paquete `io.github.hectorespert.jmediasoup` con `package-info.java`.
- [x] `0.1.6` Añadir un test de humo para que `./gradlew test` ejecute algo.
- [x] `0.1.7` Actualizar "Project state" y los comandos de ejemplo en `CLAUDE.md`.

### 0.2 Coordenadas, repositorio y licencia

- [x] `0.2.1` Fijar `group = "io.github.hectorespert"` en el build.
- [x] `0.2.2` Fijar la versión inicial `0.1.0-SNAPSHOT`.
- [x] `0.2.3` Decidir entre renombrar el subproyecto `lib` o fijar el `artifactId` en la publicación: se renombra a `jmediasoup`.
- [x] `0.2.4` Aplicar `maven-publish` y comprobar con `publishToMavenLocal` que el artefacto sale como `jmediasoup`.
- [x] `0.2.5` Crear el repositorio remoto en GitHub (hoy no hay ningún remoto configurado).
- [x] `0.2.6` Verificar el namespace `io.github.hectorespert` en Central Portal.
- [x] `0.2.7` Elegir la licencia del proyecto y añadir `LICENSE`.
- [x] `0.2.8` Revisar las licencias del código que se va a portar (pymediasoup, mediasoup-client, sdp-transform, h264-profile-level-id) y conservar sus avisos de copyright en un `NOTICE`.

### 0.3 Spike de webrtc-java

El objetivo era confirmar o descartar el stack, no dejar código definitivo.

- [x] `0.3.1` Añadir `webrtc-java` 0.20.0 al catálogo de versiones.
- [x] `0.3.2` Resolver el artefacto nativo de Linux x86_64.
- [x] `0.3.3` Seleccionar el clasificador nativo según el SO en Gradle, de forma compatible con el configuration cache.
- [x] `0.3.4` Crear y destruir un `PeerConnectionFactory` y un `RTCPeerConnection`, sin fugas ni bloqueos al cerrar.
- [x] `0.3.5` Comprobar que funciona sin dispositivos de audio ni pantalla (entorno de CI).
- [x] `0.3.6` Generar una oferta con transceivers de audio y vídeo, y guardar el SDP como fixture.
- [x] `0.3.7` Revisar en ese SDP los codecs, `extmap`, `rtcp-fb` y la presencia de líneas `a=ssrc`.
- [x] `0.3.8` Conectar un `CustomAudioSource` a un `AudioTrackSink` a través de dos peer connections locales.
- [x] `0.3.9` Conectar un `CustomVideoSource` a un `VideoTrackSink` a través de dos peer connections locales.
- [x] `0.3.10` Comprobar `setEnabled`, el estado del track y cómo se detecta su finalización (`ended`).
- [x] `0.3.11` Crear un data channel `negotiated` con `id` fijo e intercambiar mensajes.
- [x] `0.3.12` Comprobar `sendEncodings` con varios `rid` y las líneas `a=rid` y `a=simulcast` resultantes.
- [x] `0.3.13` Comprobar `RTCOfferOptions.iceRestart` y `setConfiguration`.
- [x] `0.3.14` Comprobar `getStats` a nivel de peer connection, sender y receiver.
- [x] `0.3.15` Averiguar en qué hilos llegan los callbacks nativos y si se puede bloquear en ellos.
- [x] `0.3.16` Comprobar si los tracks se pueden instanciar o simular sin cargar nativos (condiciona `FakeHandler`).
- [x] `0.3.17` Averiguar si las capacidades nativas dependen de los tracks añadidos (en aiortc hay que pasarlos a `createFactory`).
- [x] `0.3.18` Enumerar plataformas y arquitecturas soportadas.
- [x] `0.3.19` Registrar el resultado y confirmar o descartar el stack: **webrtc-java confirmado**. El código del spike se ha borrado.

**Resultado del spike** (webrtc-java 0.20.0, Linux x86_64, Java 21, sin `DISPLAY` ni servidor de audio)

Funciona entre dos peer connections locales. Falta probarlo contra un servidor mediasoup real (Fase 7) y en macOS y Windows.

| Tarea | Resultado |
|---|---|
| `0.3.2`, `0.3.3` | En Gradle el clasificador nativo hay que declararlo: sin él falla con `UnsatisfiedLinkError`. La selección por SO y CPU reutiliza el configuration cache. |
| `0.3.4` | 1000 peer connections en una factory: el RSS no crece. 300 ciclos de factory y peer connection: unos 16 kB por ciclo, sin bloqueos. |
| `0.3.5` | Hay que usar `HeadlessAudioDeviceModule`: `new PeerConnectionFactory()` falla si no hay sistema de audio. |
| `0.3.6`, `0.3.7` | Oferta con Opus, VP8, VP9, H264 y AV1, RTX, `sdes:mid`, `rtp-stream-id`, `transport-wide-cc`, `a=ssrc` y `ssrc-group:FID`. SDP en `jmediasoup/src/test/resources/sdp/webrtc-java-0.20.0/`. |
| `0.3.8`, `0.3.9` | Audio y vídeo custom llegan al sink. El audio se recibe como PCM de 16 bits, 48 kHz y mono. |
| `0.3.10` | `setEnabled(false)` silencia al receptor. `MediaStreamTrack` no tiene `stop()`. `transceiver.stop()` y renegociar termina el track remoto y dispara `ended`; el local sigue `LIVE`, y también tras `source.dispose()`. |
| `0.3.11` | Canal `negotiated` con `id` fijo, en texto y binario. Un canal fiable devuelve `getMaxRetransmits() == 0`, no `-1`. |
| `0.3.12` | `a=rid` y `a=simulcast:send r0;r1;r2` correctos, sin `a=ssrc`. `scalabilityMode` se conserva. No se ha comprobado que se emitan las tres capas. |
| `0.3.13` | `iceRestart` cambia el `ufrag` y reconecta. `setConfiguration` se aplica. |
| `0.3.14` | `getStats` funciona en los tres niveles, con `bytesSent` y `bytesReceived`. |
| `0.3.15` | Callbacks de señalización, ICE, conexión, data channel y stats en un solo hilo por factory; los sinks, en otro. **Bloquear en un callback detiene toda la factory**, incluidos los getters síncronos, y otra operación asíncrona esperada dentro del callback no termina hasta soltarlo. Otra factory no se ve afectada. |
| `0.3.16` | `MediaStreamTrack` se puede heredar sin nativos, pero `AudioTrack`, `VideoTrack`, `RTCRtpSender`, `RTCRtpReceiver`, `RTCRtpTransceiver`, `RTCDataChannel` y `RTCPeerConnection` tienen constructor privado. Las clases de datos se usan sin nativos. |
| `0.3.17` | No hacen falta tracks: una oferta con transceivers sin track da las mismas extensiones, y como único cambio de codecs añade `flexfec-03`. |
| `0.3.18` | Linux (x86_64, aarch64, aarch32), macOS (x86_64, aarch64) y Windows (x86_64, aarch64). En Linux necesita glibc (Alpine no sirve) y librerías X11, `libgbm` y `libdrm`. Jar nativo de 10 MB. |

**Consecuencias para el diseño**

- `0.5.2`: ningún trabajo bloqueante en callbacks nativos, también en los del usuario. Es obligatorio, no una preferencia.
- `0.5.5`: las abstracciones propias son necesarias para probar `FakeHandler` sin nativos.
- `0.5.7`: hay que decidir cómo reciben los nativos quienes usen la librería desde Gradle.
- `8.2.9`: `trackended` en tracks locales tendrá que emitirlo jmediasoup, porque la librería no lo hace.

### 0.4 Imagen Docker de mediasoup

Imagen elegida: `mmahdisonic/mediasoup-demo`, fijada por digest. No se ha encontrado una imagen oficial, y las de la comunidad apenas tienen documentación, así que se han inspeccionado antes de usarlas.

- [x] `0.4.1` Listar imágenes candidatas (Docker Hub, GHCR y proyectos de GitHub que publiquen imagen).
- [x] `0.4.2` Comprobar que traen mediasoup v3 en una versión conocida y reciente.
- [x] `0.4.3` Comprobar que el `Dockerfile` o el código fuente se pueden inspeccionar.
- [x] `0.4.4` Comprobar que tienen una señalización documentada que permita crear transportes, producir y consumir.
- [x] `0.4.5` Comprobar que `announcedAddress` y el rango de puertos RTC son configurables.
- [x] `0.4.6` Comprobar que la licencia es compatible con su uso en CI.
- [x] `0.4.7` Elegir la imagen y fijarla por digest, no por tag.
- [x] `0.4.8` Validar la red UDP en Linux con `--network host`.
- [ ] `0.4.9` Validar la red UDP en macOS y Windows con rango de puertos y `announcedAddress`.
- [x] `0.4.10` Si ninguna candidata cumple los criterios, volver a plantear la decisión antes de empezar la Fase 7: no hace falta, la candidata cumple.

**Resultado** (con podman, que sustituye a Docker en local)

Imagen elegida: `docker.io/mmahdisonic/mediasoup-demo@sha256:2e60f70cf77ed9ac8b3ef2adfcdb9df6666412c6c543831d5d97bdc37609c8c2`.

| Imagen | mediasoup | Código y licencia | Señalización | Conclusión |
|---|---|---|---|---|
| `mmahdisonic/mediasoup-demo` | 3.19.14 (diciembre de 2025) | Demo oficial, ISC | protoo por WebSocket, como el ejemplo de pymediasoup | Elegida |
| `mirotalk/sfu` | 3.32.2 | Público, AGPL-3.0 | socket.io, protocolo propio de la aplicación | Alternativa si hace falta una versión reciente |
| `achrafyoussef/bizcord-mediasoup` | 3.19.22 | Sin repositorio ni licencia | REST con secreto compartido | Descartada |
| `shubhamwrktalk/mediasoup-demo` | 3.18.0 | Sin Dockerfile ni documentación | Sin documentar | Descartada |
| `iabsis/mediasoup-api` | 3.19.0 | Repositorio no encontrado | Sin documentar | Descartada |
| `gbplkr/mediasoup-demo` | 3.15.7 | Sin Dockerfile ni documentación | Sin documentar | Descartada |
| `vanjoge/mediasoup-demo:v3` | 3.8.3 | Antigua | Sin documentar | Descartada |

**Procedencia:** `src/` coincide con `versatica/mediasoup-demo@e6bbe34` salvo una función `isValidPort` añadida, `node/lib` de mediasoup es idéntico al paquete de npm, y el worker tiene el mismo SHA-256 que el de la release oficial 3.19.14. `lib/` (el JavaScript compilado) solo se ha revisado buscando llamadas de red o de procesos.

**Prueba con podman 5.7 sin root, con `--cap-drop=all`:** por protoo, `getRouterRtpCapabilities`, `join` y `createWebRtcTransport` (con SCTP) funcionan, y el servidor responde a un STUN firmado por UDP en los dos transportes. Funciona con `--network host` y con los puertos publicados en `127.0.0.1`. No se ha probado DTLS ni media: eso llega con el E2E de la Fase 7.

**Lo que condiciona el cliente de señalización de la Fase 7:**

- Hay que usar protoo. La API REST solo crea `PlainTransport`, y además no crea salas: la sala nace con la primera conexión WebSocket.
- Tanto el WebSocket como la API exigen `Origin: http://localhost:4443`, aunque se publique en otro puerto.
- `createWebRtcTransport` recibe la dirección en `appData.direction`, con los valores `producer` y `consumer`.
- `MEDIASOUP_ANNOUNCED_ADDRESS` se aplica. `MEDIASOUP_MIN_PORT` y `MEDIASOUP_MAX_PORT` no afectan a los transportes WebRTC, que usan un `WebRtcServer` con puerto fijo por worker (44443 en la prueba). Se lanza un worker por CPU, así que al publicar puertos hay que cubrir ese rango.
- Sirve HTTP sin TLS.

**Riesgos:** la publica una cuenta personal que podría borrarla (habría que republicarla o construirla desde el demo oficial), y va 13 versiones menores por detrás de mediasoup, así que los tests solo validan esa versión del servidor (ver `0.5.14`).

**El demo público no sirve:** `v3demo.mediasoup.org` rechaza las conexiones sin su `Origin`, y falsearlo para usar un servidor ajeno no es una opción. Los tests automáticos de pymediasoup tampoco lo usan: solo usan `FakeHandler`.

### 0.5 Decisiones de diseño

Cada una se confirma y se anota en la sección [Decisiones](#decisiones) de este documento.

- [x] `0.5.1` Modelo asíncrono: `CompletableFuture`. Descartadas la API bloqueante (riesgo de interbloqueo con la cola del transporte), la doble API y las librerías reactivas.
- [x] `0.5.2` Modelo de hilos: una cola serializada por transporte, que también entrega los eventos. Un `Executor` inyectable para los listeners se puede añadir más tarde sin romper nada.
- [x] `0.5.3` Eventos: `connect` y `produce` obligatorios al crear el transporte, y el resto como listeners tipados. Descartados el emisor por nombre y una interfaz `Listener` por clase.
- [x] `0.5.4` Tipos de texto cerrado (`MediaKind`, `DtlsRole`, `ConnectionState`...): enums con su valor JSON.
- [x] `0.5.5` Tipos de webrtc-java en la API pública, y abstracciones propias solo dentro del handler (sender, receiver, data channel). Descartados los tipos propios con `unwrap()`.
- [x] `0.5.6` Módulos: artefacto único `jmediasoup` y handler pluggable por interfaz.
- [x] `0.5.7` Alcance de dependencias y nativos: ver [Decisiones](#decisiones). Las variantes de Gradle que eligen los nativos solas quedan para la Fase 13.
- [x] `0.5.8` Nulos: JSpecify.
- [x] `0.5.9` Tipo de `appData`: `Map<String, Object>` inmutable.
- [x] `0.5.10` Logging: SLF4J 2. Descartado `System.Logger`.
- [x] `0.5.11` Se publica `module-info.java` desde el principio. Todas las dependencias ya son módulos con nombre (`webrtc.java`, `org.slf4j`, `com.fasterxml.jackson.annotation`, `org.jspecify`).
- [x] `0.5.12` Representación del SDP parseado: `Map<String, Object>`, como `sdp-transform`. Es interna, así que se puede tipar más adelante.
- [x] `0.5.13` Revisar alternativas a los ports: `libSDPtransform-android` solo contiene un `.aar` binario, sin fuentes ni licencia. Tampoco sirven `crow-misia/sdp` (Kotlin, sin nombre de módulo y con modelo tipado), `medooze/sdp` (archivado), las librerías SDP de la era SIP ni un port Java de un demo sin licencia. No hay ninguna librería Java de `h264-profile-level-id`.
- [x] `0.5.14` Versiones de servidor soportadas: mediasoup v3.
- [x] `0.5.15` Confirmar los modelos con `record` y anotaciones de Jackson, sin `jackson-databind` en tiempo de ejecución.
- [x] `0.5.16` Confirmar los ports propios de `sdp-transform` y `h264-profile-level-id`, como paquetes internos.

### 0.6 CI y calidad

- [x] `0.6.1` Workflow de GitHub Actions: `./gradlew build` en push a `main` y en PR (`.github/workflows/build.yml`). Las acciones van fijadas por SHA. El workflow se crea desde la web de GitHub, porque el token de `gh` de las sesiones no tiene el permiso `workflow`.
- [x] `0.6.2` Caché de Gradle con `setup-gradle` y `cache-provider: basic` (la caché de GitHub Actions, en lugar de la comercial que usa por defecto). Solo escriben en ella los builds de `main`.
- [x] `0.6.3` Publicar los informes de tests como artefacto del workflow (`test-reports`, 14 días).
- [x] `0.6.4` Formateador: Spotless, que forma parte de `./gradlew build`. En Java aplica palantir-java-format, quita los imports sin usar y pone la cabecera de licencia SPDX (salvo en `package-info.java` y `module-info.java`). En los `.gradle.kts` aplica ktlint. En Markdown, YAML, TOML y ficheros de Git quita los espacios al final de línea y asegura el salto final.
- [x] `0.6.5` Activar actualizaciones automáticas de dependencias. Hecho con Dependabot (`.github/dependabot.yml`): revisa una vez al mes el catálogo y los plugins de Gradle y los SHA de las Actions, abre una PR agrupada por ecosistema y solo propone versiones publicadas hace al menos 14 días (`cooldown`). También actualiza el wrapper de Gradle; su commit deja `gradlew.bat` con CRLF en el índice, contra lo que dice `.gitattributes`, y se corrige con `git add --renormalize gradlew.bat`. Las PRs de Actions tocan `.github/workflows/`, así que el merge se hace desde la web.

### 0.7 Aplicar las decisiones al build

- [x] `0.7.1` `module-info.java` del módulo `io.github.hectorespert.jmediasoup`, con `requires transitive org.jspecify`. Todavía no exporta nada: javac no deja exportar un paquete que solo tiene `package-info.java`, así que el `exports` del paquete raíz llega con su primer tipo público (`1.1.1`).
- [x] `0.7.2` Comprobar que los tests se ejecutan en el module path de Gradle. Por defecto Gradle los ejecuta en el classpath; con `org.gradlex.java-module-testing` 1.8.1 se ejecutan dentro del módulo, y `SmokeTest.testsRunInTheLibraryModule` lo comprueba.
- [x] `0.7.3` Fijar el nombre de los paquetes internos, que `module-info.java` no exporta: `io.github.hectorespert.jmediasoup.internal.*`.
- [x] `0.7.4` Añadir JSpecify como `api` y `@NullMarked` en `package-info.java` (JSpecify 1.0.1).

**Aceptación de la Fase 0**

- `./gradlew build` pasa sin restos de la plantilla, en local y en CI.
- `publishToMavenLocal` genera `io.github.hectorespert:jmediasoup`.
- El stack WebRTC y la imagen Docker están confirmados, o se ha replanteado la decisión.
- Las decisiones de diseño de la sección 0.5 están anotadas, y el módulo JPMS compila y pasa los tests.

## Fase 1: fundamentos

### 1.1 Errores

Origen: `pymediasoup/errors.py`

- [x] `1.1.1` Excepción base del proyecto (equivalente a `Error`): `MediasoupClientException`, checked. Es el primer tipo público, así que añade `exports io.github.hectorespert.jmediasoup` a `module-info.java`.
- [x] `1.1.2` `UnsupportedError`: `UnsupportedException`.
- [x] `1.1.3` `InvalidStateError`: `InvalidStateException`.
- [x] `1.1.4` Decidir qué excepción de Java sustituye a cada `TypeError` del original: `IllegalArgumentException`, o `NullPointerException` si falta un argumento obligatorio. Los `TypeError` por falta del listener `connect` o `produce` desaparecen, porque se pasan al crear el transporte.

### 1.2 Eventos y observers

Origen: `pymediasoup/emitter.py`, `pyee`

- [x] `1.2.1` Colección de listeners segura ante concurrencia, con alta y baja: `internal.event.Listeners<T>` y `RunnableListeners`, sobre `CopyOnWriteArrayList`.
- [x] `1.2.2` Aislar errores: la excepción de un listener no impide avisar a los demás y queda registrada con SLF4J, que se añade aquí como `implementation` (2.0.20; `slf4j-simple` solo en tests).
- [x] `1.2.3` Contrato para listeners que devuelven un resultado asíncrono (sustituye a `emit_for_results`): `internal.event.AsyncHandlers`, con `run` para los que no devuelven valor y `call` para los que sí. Las interfaces `ConnectHandler` y `ProduceHandler` se crean con sus tipos en `4.3.5` y `4.3.7`.
- [x] `1.2.4` Error claro si un listener obligatorio falla o devuelve un resultado nulo: `MediasoupClientException` con un mensaje distinto para cada caso y la causa original. No pueden faltar, porque se pasan al crear el transporte (`0.5.3`). ⚠️ no copiar: `ids[0]` falla con `IndexError` si el listener de `produce` falla o no devuelve nada.
- [x] `1.2.5` Tests de orden de entrega, baja durante la entrega y propagación de errores, incluido un test de concurrencia.
- [x] `1.2.6` Suscripción devuelta al registrar un listener, para darse de baja: `Subscription`, en el paquete raíz.

### 1.3 Parámetros RTP

Origen: `pymediasoup/rtp_parameters.py`

- [x] `1.3.1` `MediaKind`, enum con `value()` y `fromValue()`.
- [x] `1.3.2` `RtcpFeedback`.
- [x] `1.3.3` `RtpCodecCapability`.
- [x] `1.3.4` `RtpCodecParameters`.
- [x] `1.3.5` Tipo común de codec para que `matchCodecs` acepte capacidades y parámetros (`Codec` y `RtpCodec` en el original): interfaz sellada `RtpCodec`.
- [x] `1.3.6` `RtpHeaderExtension` y su `direction` (`RtpHeaderExtensionDirection`).
- [x] `1.3.7` `RtpHeaderExtensionParameters`.
- [x] `1.3.8` `RtpCapabilities`, con `fecMechanisms` como en pymediasoup.
- [x] `1.3.9` `RTX` (SSRC de retransmisión): `Rtx`, con el SSRC como `long`.
- [x] `1.3.10` `RtpEncodingParameters`, con todos sus campos opcionales, builder y `toBuilder()`. Añade `active` de mediasoup-client, y `scaleResolutionDownBy` y `maxFramerate` son `Double` (⚠️ en pymediasoup son `int`). La prioridad es el enum `Priority`.
- [x] `1.3.11` `RtcpParameters`.
- [x] `1.3.12` `RtpParameters`, con `msid` de mediasoup-client.
- [x] `1.3.13` `ExtendedCodec`, público porque lo recibe el handler (`0.5.6`).
- [x] `1.3.14` `ExtendedHeaderExtension`.
- [x] `1.3.15` `ExtendedRtpCapabilities`.
- [x] `1.3.16` Representar los `parameters` de codec con tipos estables al comparar (un `apt` puede llegar como número o como texto): mapa con los tipos JSON y comparación con `internal.ParameterValues`.
- [x] `1.3.17` Validar en el constructor los campos obligatorios (en el original lo hace pydantic), y también rangos y formatos, con `internal.Checks`.
- [x] `1.3.18` Copias defensivas de listas y mapas (sustituye a `copy(deep=True)`). Los mapas conservan el orden de inserción.

### 1.4 Parámetros SCTP

Origen: `pymediasoup/sctp_parameters.py`

- [x] `1.4.1` `NumSctpStreams`, con los campos `os()` y `mis()` (`OS` y `MIS` en JSON, `1.8.4`).
- [x] `1.4.2` `SctpCapabilities`.
- [x] `1.4.3` `SctpParameters`. Solo `port` es obligatorio, para aceptar los campos de mediasoup 3.19 (`OS`, `MIS`, `maxMessageSize`) y los de servidores más nuevos (`maxSendMessageSize`, `maxReceiveMessageSize`).
- [x] `1.4.4` `SctpStreamParameters`. `ordered` queda `null` si no llega, como el resto de opcionales; deducirlo es tarea de `9.3.3`.
- [x] `1.4.5` Validar la combinación `ordered`, `maxPacketLifeTime` y `maxRetransmits` descrita en el original. Las contradicciones (los dos máximos, u `ordered: true` con uno) fallan en el constructor, porque no dependen del contexto.

### 1.5 Parámetros de transporte

Origen: `pymediasoup/models/transport.py`

- [x] `1.5.1` `IceParameters`, incluido `iceLite`.
- [x] `1.5.2` `IceCandidate`, con enums de `protocol` (`TransportProtocol`), `type` y `tcpType`. Guarda `address` y el obsoleto `ip`, con al menos uno obligatorio, y `effectiveAddress()` lee el que haya. La prioridad es `long` (32 bits sin signo).
- [x] `1.5.3` `DtlsFingerprint`, con el enum `FingerprintAlgorithm`.
- [x] `1.5.4` `DtlsRole`.
- [x] `1.5.5` `DtlsParameters`. `role` queda `null` si no llega, que significa `auto`.
- [x] `1.5.6` `ConnectionState`.
- [x] `1.5.7` `IpVersion`, con valor numérico.
- [x] `1.5.8` `PlainRtpParameters`.
- [x] `1.5.9` `TransportOptions`, con builder y `toBuilder()` para añadir los ajustes locales a lo que devuelve el servidor. Sin `additionalSettings` ni `proprietaryConstraints`, que se deciden en `10.2.8`.
- [x] `1.5.10` Opciones internas de transporte (`InternalTransportOptions`): movida a `4.3.1`, porque necesita el `HandlerFactory` de `4.1.7`.
- [x] `1.5.11` Enum `TransportDirection` (`send`, `recv`).
- [x] `1.5.12` Modelo de `RTCIceServer` propio, si `0.5.5` lo requiere: record `IceServer` (`urls`, `username`, `credential`, como en W3C), con la credencial oculta en `toString()`, y enum `IceTransportPolicy`.

### 1.6 Opciones de productores y consumidores

Origen: `producer.py`, `consumer.py`, `data_producer.py`, `data_consumer.py`

- [x] `1.6.1` `ProducerCodecOptions`, con sus nueve campos y `opusNack` de mediasoup-client, y builder.
- [x] `1.6.2` `ProducerOptions`, con los valores por defecto del original (`stopTracks = true`, `disableTrackOnPause = true`, `zeroRtpOnPause = false`) como `boolean` del builder. El track es obligatorio, y añade `streamId` de mediasoup-client.
- [x] `1.6.3` `ConsumerOptions`, con `streamId` de mediasoup-client.
- [x] `1.6.4` `DataProducerOptions`, con builder y las mismas reglas de fiabilidad que `SctpStreamParameters`.
- [x] `1.6.5` `DataConsumerOptions`.

### 1.7 Abstracciones de medios

Origen: `pymediasoup/types/webrtc.py` y los tipos de aiortc que usa el original

La API pública usa los tipos de webrtc-java (`0.5.5`). Sender y receiver no necesitan abstracción, porque Producer y Consumer solo los exponen y operan a través del handler con el `localId`. El data channel sí, porque DataProducer y DataConsumer operan sobre él y `RTCDataChannel` no se puede crear sin nativos.

- [x] `1.7.1` Subclase de prueba de `MediaStreamTrack` de webrtc-java, que se instancia sin nativos (según `0.3.16`): `FakeMediaStreamTrack`, en el paquete `fake` de los tests.
- [x] `1.7.2` ~~Interfaz interna de sender~~. Descartada: el resultado del handler lleva un `@Nullable RTCRtpSender`, `null` con `FakeHandler`, como en pymediasoup.
- [x] `1.7.3` ~~Interfaz interna de receiver~~. Descartada: el resultado del handler lleva un `@Nullable RTCRtpReceiver`.
- [x] `1.7.4` Interfaz de data channel (`label`, `protocol`, `readyState`, `send`, `close`, buffer y eventos): `handler.DataChannel`, pública porque el handler es pluggable (`0.5.6`), con un `Observer` de eventos. Los mensajes son `DataChannelMessage`, en el paquete raíz, que copia los bytes.
- [x] `1.7.5` Implementaciones falsas para los tests: `fake.FakeDataChannel`, que el test abre, alimenta y observa.

### 1.8 JSON

El usuario convierte los modelos con su propio `ObjectMapper` (`0.5.15`), así que todo se configura con anotaciones.

- [x] `1.8.1` Añadir `jackson-annotations` como `api` (2.22, la que usan Jackson 2.22 y 3.2), y `jackson-databind` 2 (2.22.4) y 3 (3.2.4) solo para tests.
- [x] `1.8.2` Anotar los modelos para omitir nulos e ignorar campos desconocidos (`@JsonInclude(NON_NULL)` y `@JsonIgnoreProperties(ignoreUnknown = true)` en cada record JSON; no en `ProducerOptions`, que lleva un track). Los componentes primitivos obligatorios llevan `@JsonProperty(required = true)`, para que su ausencia falle en vez de dar 0.
- [x] `1.8.3` Serializar enums con el valor que espera mediasoup: `@JsonValue` en `value()` y `@JsonCreator` en `fromValue()`. Un valor desconocido falla con el mensaje de `fromValue`.
- [x] `1.8.4` Nombres que no siguen camelCase, como `OS` y `MIS` de `NumSctpStreams` y `SctpParameters`, con `@JsonProperty`.
- [x] `1.8.5` Comprobar que el round-trip conserva los tipos de los `parameters` de codec, y su orden.
- [x] `1.8.6` Tests de round-trip con Jackson 2 y con Jackson 3: `json.JsonRoundTripTest` ejecuta cada caso con los dos (`@WithEachJackson`). Comprobado también desde un módulo JPMS consumidor, sin `opens`.
- [x] `1.8.7` `IceServer.urls`: aceptar también un texto suelto, que el estándar W3C permite (`"urls": "turn:..."`), con `ACCEPT_SINGLE_VALUE_AS_ARRAY`.

### 1.9 Fixtures

Origen: `tests/fake_parameters.py`, `tests/fake_handler.py`

- [x] `1.9.1` Portar `generateRouterRtpCapabilities`: `fixtures/router-rtp-capabilities.json` y `fake.FakeParameters.routerRtpCapabilities()`. Los JSON se han generado desde el código del original con `ast`, sin copiarlos a mano.
- [x] `1.9.2` Portar las capacidades RTP nativas de `FakeHandler`: `fixtures/native-rtp-capabilities.json`.
- [x] `1.9.3` Portar `generateTransportRemoteParameters`, como `TransportOptions` con un id nuevo en cada llamada. Sin el campo `family` de los candidatos, que mediasoup v3 ya no manda.
- [x] `1.9.4` Portar `generateConsumerRemoteParameters`, por codec (`audio/opus`, `audio/ISAC`, `video/VP8`). ⚠️ no copiar: en ISAC la primera header extension usa la variable `id` como clave en vez de `"id"`.
- [x] `1.9.5` Portar `generateDataProducerRemoteParameters` y `generateDataConsumerRemoteParameters`. ⚠️ no copiar: pydantic deja `ordered = True` junto a `maxPacketLifeTime`, que es contradictorio; aquí `ordered` queda ausente.
- [x] `1.9.6` Round-trip JSON de todos los fixtures: `json.FixturesRoundTripTest` recorre `fixtures/` con Jackson 2 y 3, y comprueba que el objeto se conserva y que todo valor de entrada sigue en la salida.

**Aceptación de la Fase 1** (cumplida)

- Todos los fixtures se leen, se validan y se reescriben sin perder datos (`1.9.6`).
- Las entradas no válidas fallan al construir el objeto, con un mensaje útil (tests de `Checks` y de cada modelo, y `JsonRoundTripTest`).

## Fase 2: ORTC

### 2.1 Port de h264-profile-level-id

Origen: paquete `h264-profile-level-id`. Va en un paquete interno (`0.5.16`). pymediasoup solo usa `isSameProfile` y `generateProfileLevelIdForAnswer`, pero se porta entero desde `versatica/h264-profile-level-id` 2.3.3, el original en JS que usa mediasoup-client, para poder portar todos sus tests. Está en `internal.h264`.

- [x] `2.1.1` Constantes de perfiles y niveles: enums `Profile` y `Level` (con su `level_idc`), el record `ProfileLevelId` y las tablas de patrones de perfil y límites de nivel.
- [x] `2.1.2` Parsear una cadena `profile-level-id`: `parseProfileLevelId`, que solo acepta 6 dígitos hexadecimales (el original, con `parseInt`, acepta signo y basura al final).
- [x] `2.1.3` Convertir un perfil y nivel a cadena: `profileLevelIdToString`, y también `profileToString` y `levelToString`.
- [x] `2.1.4` Leer el parámetro del `fmtp`, con su valor por defecto si falta: `parseSdpProfileLevelId`, que acepta el valor como texto o como número porque los `parameters` conservan el tipo JSON.
- [x] `2.1.5` `isSameProfile`, y también `isSameProfileAndLevel`.
- [x] `2.1.6` `generateProfileLevelIdForAnswer` (`generateProfileLevelIdStringForAnswer` en el original), con `IllegalArgumentException` donde el original lanza `TypeError`, y `level-asymmetry-allowed` como `true`, `1` o `"1"`. También `supportedLevel`.
- [x] `2.1.7` Port de los casos de prueba de la librería original: `H264ProfileLevelIdTest`, todos salvo el de un perfil desconocido, que un enum de Java no puede representar, y con casos propios para valores numéricos y con signo.

### 2.2 Modos de escalabilidad

Origen: `pymediasoup/scalability_modes.py`

- [x] `2.2.1` `ScalabilityMode` (capas espaciales y temporales): record en `internal.ortc`. Es interno aunque mediasoup-client exporta `parseScalabilityMode`; solo lo usa el handler.
- [x] `2.2.2` `parse`, que solo mira el prefijo (acepta `_KEY` y otros sufijos) y devuelve una capa de cada si el modo falta o no es válido. ⚠️ no copiar: la regex usa `\\d` en un raw string, así que el original siempre devuelve 1 y 1.
- [x] `2.2.3` Tests de `L1T3`, `S3T3`, `L3T3_KEY`, cadena vacía y nula, y valores no válidos, más los casos de `test.ts` de mediasoup-client.

### 2.3 Comparación de codecs

Origen: `pymediasoup/ortc.py`

- [x] `2.3.1` Constantes `RTP_PROBATOR_MID`, `RTP_PROBATOR_SSRC` y `RTP_PROBATOR_CODEC_PAYLOAD_TYPE`, en `internal.ortc.Ortc`.
- [x] `2.3.2` `isRtxCodec`, sin distinguir mayúsculas, como mediasoup-client.
- [x] `2.3.3` `matchCodecs`: `mimeType` (sin distinguir mayúsculas), `clockRate` y `channels`. Un codec de audio sin `channels` cuenta como 1 y en vídeo se ignoran, como hace mediasoup-client al normalizar.
- [x] `2.3.4` `matchCodecs`: H264, `packetization-mode`, que como en mediasoup-client solo se compara en modo estricto (pymediasoup lo compara siempre).
- [x] `2.3.5` `matchCodecs`: H264 estricto, `isSameProfile`.
- [x] `2.3.6` `matchCodecs`: H264 con `modify`, usando `generateProfileLevelIdForAnswer`. ⚠️ no copiar: el original muta el codec de entrada. Aquí `matchCodecs` devuelve un `CodecMatch` (o `null` si no coinciden) con los parámetros negociados de los dos codecs, como mediasoup-client cuando modifica ambos.
- [x] `2.3.7` `matchCodecs`: VP9 estricto, `profile-id`, con 0 por defecto y comparando número y texto.
- [x] `2.3.8` `reduceRtcpFeedback`, con un `parameter` vacío equivalente a ausente.
- [x] `2.3.9` `matchHeaderExtensions`, que como en mediasoup-client solo compara `kind` si los dos lo tienen.

### 2.4 Capacidades extendidas

Sigue el modelo de mediasoup-client 3.26: capacidades extendidas separadas para enviar y para recibir, cada una con las capacidades nativas de esa dirección.

- [x] `2.4.1` `getExtendedRtpCapabilities`: codecs en modo estricto, con el orden del router o, con `preferLocalCodecsOrder`, el local. Cada lado lleva sus parámetros negociados (`profile-level-id` de H264). Un codec emparejado sin `preferredPayloadType` lanza `IllegalArgumentException`.
- [x] `2.4.2` `getExtendedRtpCapabilities`: emparejar RTX por `apt`, comparando su valor numérico, solo si los dos lados lo tienen.
- [x] `2.4.3` `getExtendedRtpCapabilities`: extensiones de cabecera, en el orden del router.
- [x] `2.4.4` `getExtendedRtpCapabilities`: invertir `direction` respecto al router; sin dirección queda `sendrecv`.
- [x] `2.4.5` `getRecvRtpCapabilities` y `getSendRtpCapabilities`: codecs y RTX, con los payload types del router o los locales.
- [x] `2.4.6` `getRecvRtpCapabilities` y `getSendRtpCapabilities`: filtrar las extensiones no válidas en su dirección.
- [x] `2.4.7` `canSend`, sobre las capacidades de envío.
- [x] `2.4.8` `canReceive`, sobre las capacidades de recepción.

Diferencia con mediasoup-client: allí `matchCodecs` modifica las capacidades del router al negociar H264, y el cálculo de recepción en `load` cambia el `profile-level-id` que ve después el de envío. Aquí los modelos son inmutables y cada cálculo parte de las capacidades originales.

### 2.5 Parámetros de envío

- [x] `2.5.1` `getSendingRtpParameters`: codecs y RTX, con los payload types y parámetros locales. `mid` y encodings vacíos, y RTCP vacío como en mediasoup-client.
- [x] `2.5.2` `getSendingRtpParameters`: extensiones de cabecera que se pueden enviar, del tipo pedido o sin tipo. ⚠️ no copiar: pymediasoup descarta las que no tienen tipo.
- [x] `2.5.3` `getSendingRemoteRtpParameters`: usar los parámetros remotos del codec.
- [x] `2.5.4` `getSendingRemoteRtpParameters`: reducir el feedback (Transport-CC, si no REMB, si no ninguno).
- [x] `2.5.5` `reduceCodecs` sin codec indicado: el primero y su RTX.
- [x] `2.5.6` `reduceCodecs` con codec indicado, comparando en modo estricto como mediasoup-client. ⚠️ no copiar: el `break` solo se ejecuta si hay RTX, así que sin RTX sigue añadiendo coincidencias.
- [x] `2.5.7` `reduceCodecs`: `UnsupportedException` si no hay coincidencia o la lista está vacía.
- [x] `2.5.8` `generateProbatorRtpParameters`. Sin codecs de vídeo lanza `IllegalArgumentException`.

**Aceptación de la Fase 2** (cumplida)

- Cada función de `ortc.py` tiene tests, incluidos H264, VP9, RTX y el caso sin coincidencias (`OrtcMatchingTest`, `OrtcCapabilitiesTest`, `OrtcSendingTest`, `ScalabilityModeTest` y `H264ProfileLevelIdTest`).
- Los resultados con los fixtures coinciden con los de pymediasoup o mediasoup-client, salvo los bugs marcados. Los valores esperados se han sacado leyendo el código de mediasoup-client 3.26, sin ejecutarlo.

## Fase 3: SDP

### 3.1 Port de sdp-transform: parser

Origen: paquete `sdp-transform`

Va en un paquete interno (`0.5.16`), y el SDP parseado es un `Map<String, Object>` mutable que conserva el orden de inserción (`0.5.12`).

Portado de `clux/sdp-transform` 3.0.0, la versión que usa mediasoup-client 3.26, en `internal.sdptransform` (`Grammar` y `Parser`). Las regex son las del original; en Java `\s` y `\S` solo cubren espacios ASCII.

- [x] `3.1.1` Estructura de la gramática, con regex, nombre, campos y formato por tipo de línea (`Grammar.Rule`). Los formatos son funciones del mapa de valores, como en el original.
- [x] `3.1.2` Parser de líneas y separación entre sesión y secciones `m=`. Acepta `\r\n`, `\r` y `\n`, e ignora las líneas que no son `x=...` con una minúscula.
- [x] `3.1.3` Conversión numérica de campos, con la misma regla que el original (`String(Number(v)) === v`). Los números se guardan como los lee Jackson: `Integer`, `Long` o `BigInteger` según el tamaño, y `Double` si tienen decimales.
- [x] `3.1.4` Líneas de sesión: `v`, `o`, `s`, `i`, `u`, `e`, `p`, `z`, `r`, `t`, `c`, `b`.
- [x] `3.1.5` Línea `m=` con tipo, puerto, protocolo y payloads.
- [x] `3.1.6` `a=rtpmap`, `a=fmtp`, `a=rtcp` y `a=rtcp-fb`.
- [x] `3.1.7` `a=extmap`, `a=extmap-allow-mixed`.
- [x] `3.1.8` `a=ice-ufrag`, `a=ice-pwd`, `a=ice-lite`, `a=ice-options`.
- [x] `3.1.9` `a=candidate`, `a=end-of-candidates`, `a=remote-candidates`.
- [x] `3.1.10` `a=fingerprint`, `a=setup`, `a=crypto`, `a=connection`.
- [x] `3.1.11` `a=mid`, `a=msid`, `a=msid-semantic`, `a=group`.
- [x] `3.1.12` `a=ssrc`, `a=ssrc-group`.
- [x] `3.1.13` Dirección (`sendrecv`, `sendonly`, `recvonly`, `inactive`), `a=rtcp-mux`, `a=rtcp-rsize`.
- [x] `3.1.14` `a=ptime`, `a=maxptime`, `a=framerate`.
- [x] `3.1.15` `a=rid`, `a=simulcast`, `a=simulcast:` del borrador 03.
- [x] `3.1.16` `a=sctp-port`, `a=max-message-size`, `a=sctpmap`.
- [x] `3.1.17` Resto de atributos de la gramática original (`imageattr`, `source-filter`, `ts-refclk`, `mediaclk`, BFCP...), y conservar los desconocidos en `invalid`. También `parseRemoteCandidates`, `parseImageAttributes` y `parseSimulcastStreamList`.
- [x] `3.1.18` `parseParams`. Una clave sin valor queda con `null`, como el `undefined` del original; las utilidades de la `3.3` lo cambian a `''`, como hace mediasoup-client al validar las capacidades. El paso de `profile-level-id` a texto también va en la `3.3` (`3.3.2`), porque el original lo hace en `extractRtpCapabilities`, no en `parseParams`.
- [x] `3.1.19` `parsePayloads`, convirtiendo como el `Number` de JavaScript (`NaN` para lo que no es un número).
- [x] `3.1.20` Port de los casos de prueba de parseo de la librería original (`ParserTest`, sin las comprobaciones del writer, que van en la `3.2.6`, y `CoverageTest`), con sus SDP en `src/test/resources/sdp/`.

### 3.2 Port de sdp-transform: generador

En `internal.sdptransform.Writer`. Como el original, escribe las líneas en el orden de la gramática y completa la sesión que recibe (`version`, `name` y `payloads`). Las conversiones de JavaScript que usan el parser y el writer están en `internal.sdptransform.JavaScript`.

- [x] `3.2.1` Formateador de líneas con los especificadores de la gramática (`%s`, `%d`, `%v`, `%%`). Escribe los valores como JavaScript: `%d` convierte con `Number()`, un valor ausente es `undefined` y los decimales salen con los dígitos mínimos. Los enteros se escriben exactos, aunque pasen de 53 bits.
- [x] `3.2.2` Orden de líneas de sesión.
- [x] `3.2.3` Orden de líneas de cada sección `m=`.
- [x] `3.2.4` Omitir los campos ausentes.
- [x] `3.2.5` Fin de línea `\r\n`.
- [x] `3.2.6` Port de los casos de prueba de escritura (`ComposeTest`), incluidas las comprobaciones del writer de `parse.test.js` (`icelite`, `jsep`).
- [x] `3.2.7` Round-trip parse y write con los SDP guardados en `0.3.6`: se conservan todas las líneas salvo `a=msid-semantic: WMS` sin token, que el original también reescribe con dos espacios.
- [x] `3.2.8` Comprobar que webrtc-java acepta un SDP generado por el port: movida a `5.1.15`, porque necesita los nativos de la `5.1.1`.

### 3.3 Utilidades comunes

Origen: `commonUtils.ts` y `unifiedPlanUtils.ts` de mediasoup-client 3.26 (antes `pymediasoup/handlers/sdp/common_utils.py`, `unified_plan_utils.py`), en `internal.sdp` (`CommonUtils` y `UnifiedPlanUtils`). Un SDP que no encaja en los modelos (un codec sin clock rate, un SSRC de más de 32 bits) lanza `IllegalArgumentException`, y los `Error` del original lanzan `MediasoupClientException`.

- [x] `3.3.1` `extractRtpCapabilities`: codecs de todas las secciones de audio y vídeo, indexados por payload type (BUNDLE), como mediasoup-client. ⚠️ no copiar: pymediasoup solo lee la primera sección de cada tipo.
- [x] `3.3.2` `extractRtpCapabilities`: parámetros desde `fmtp`, con `profile-level-id` como texto y las claves sin valor (`null` en `parseParams`) como `''`.
- [x] `3.3.3` `extractRtpCapabilities`: `rtcp-fb`; con payload `*` se aplica a todos los codecs del tipo salvo RTX. ⚠️ no copiar: pymediasoup lo ignora.
- [x] `3.3.4` `extractRtpCapabilities`: extensiones de cabecera indexadas por tipo e id, omitiendo las cifradas.
- [x] `3.3.5` `extractDtlsParameters`: `a=setup` y `a=fingerprint` de sesión o, si faltan, de la primera sección con puerto. Un `setup` desconocido deja el rol a `null`. ⚠️ no copiar: pymediasoup compara con `"activate"`, y el valor correcto es `"active"`.
- [x] `3.3.6` `getCname`.
- [x] `3.3.7` `getRtpEncodings`: SSRC y grupos `FID`, en el orden de los SSRC. Como mediasoup-client, solo añade el SSRC de RTX si hay más de un codec. ⚠️ pymediasoup usa un `set`, sin orden definido.
- [x] `3.3.8` Tests con SDP con RTX, sin RTX y sin SSRC, usando las ofertas reales de webrtc-java de `0.3.6`.

### 3.4 Secciones de media

Origen: `RemoteMediaSection.ts` de mediasoup-client 3.26 (antes `pymediasoup/handlers/sdp/media_section.py`), en `internal.sdp` con sus nombres: `RemoteMediaSection`, `RemoteAnswerMediaSection` y `RemoteOfferMediaSection`. Cada sección es un objeto de media de sdp-transform mutable. Las opciones de codec de la respuesta van en la `11.1`, el simulcast en la `11.3` y las secciones `application` en la `9.4`. `plainRtpParameters` no se porta: ningún handler de mediasoup-client lo usa.

- [x] `3.4.1` `getCodecName`.
- [x] `3.4.2` Base `MediaSection`: ICE y candidatos (`component = 1`, `end-of-candidates`, `renomination`), con la dirección de `effectiveAddress()` y el `tcptype`.
- [x] `3.4.3` `MediaSection`: propiedades `mid` y `closed` (puerto 0).
- [x] `3.4.4` `MediaSection.setIceParameters`.
- [x] `3.4.5` `MediaSection.disable`, con `pause` y `resume` de mediasoup-client.
- [x] `3.4.6` `MediaSection.close`.
- [x] `3.4.7` `AnswerMediaSection`: cabecera, conexión y puerto.
- [x] `3.4.8` `AnswerMediaSection`: audio y vídeo, `rtp`, `fmtp`, `rtcpFb` y `payloads`.
- [x] `3.4.9` `AnswerMediaSection`: incluir solo las extensiones presentes en la oferta.
- [x] `3.4.10` `AnswerMediaSection`: `extmapAllowMixed`, `rtcp-mux`, `rtcp-rsize`.
- [x] `3.4.11` `AnswerMediaSection.setDtlsRole`.
- [x] `3.4.12` `OfferMediaSection`: cabecera, conexión, puerto y protocolo.
- [x] `3.4.13` `OfferMediaSection`: audio y vídeo, `msid`, `rtp`, `fmtp`, `rtcpFb` y `ext`.
- [x] `3.4.14` `OfferMediaSection`: `ssrcs` y `ssrcGroups`, con RTX.
- [x] `3.4.15` `OfferMediaSection.setDtlsRole` (siempre `actpass`).
- [x] `3.4.16` Comprobar el resultado contra `RemoteMediaSection.ts`: los tests comparan el SDP escrito de cada sección con el que sale de seguir el código de mediasoup-client, sin ejecutarlo.

### 3.5 RemoteSdp

Origen: `RemoteSdp.ts` de mediasoup-client 3.26 (antes `pymediasoup/handlers/sdp/remote_sdp.py`), en `internal.sdp.RemoteSdp`. Los `Error` del original (sección inexistente) lanzan `MediasoupClientException`. `send` con opciones de codec llega en la `11.1`, `muxMediaSectionSimulcast` en la `11.3` y `sendSctpAssociation` y `receiveSctpAssociation` en la `9.4`.

- [x] `3.5.1` Constructor con el SDP base (`origin`, `name`, `timing`) y `a=ice-options:ice2`. El usuario del `origin` es `jmediasoup-v<versión>`, con la versión que el build graba en el descriptor del módulo (`jmediasoup` si no la hay).
- [x] `3.5.2` Indicador `ice-lite`.
- [x] `3.5.3` Fingerprint: el último de los parámetros DTLS, como mediasoup-client. ⚠️ no copiar: pymediasoup filtra `sha-256` porque aiortc solo soporta ese; libwebrtc acepta los demás. Se comprueba con webrtc-java en la `5.1.15`.
- [x] `3.5.4` Grupo `BUNDLE` y `msid-semantic`.
- [x] `3.5.5` `updateIceParameters`.
- [x] `3.5.6` `updateDtlsRole`, sustituyendo los parámetros DTLS en vez de modificarlos.
- [x] `3.5.7` `getNextMediaSectionIdx` y `MediaSectionIdx`.
- [x] `3.5.8` `send`, quitando la extensión Dependency Descriptor salvo con AV1 o H264.
- [x] `3.5.9` `receive`, reciclando solo secciones cerradas del mismo tipo.
- [x] `3.5.10` `disableMediaSection`, con `pauseMediaSection`, `resumeSendingMediaSection` y `resumeReceivingMediaSection` de mediasoup-client.
- [x] `3.5.11` `closeMediaSection`, sin cerrar nunca la primera sección.
- [x] `3.5.12` `getSdp`, incrementando `sessionVersion`.
- [x] `3.5.13` `addMediaSection`, `replaceMediaSection` y `regenerateBundleMids`.
- [x] `3.5.14` Tests de añadir, cerrar y reciclar secciones.

**Aceptación de la Fase 3** (cumplida salvo el segundo punto, que pasa a la `5.1.15`)

- El port pasa los casos de prueba de `sdp-transform` que se hayan portado.
- `RemoteSdp` genera ofertas y respuestas que webrtc-java acepta en un test con peer connections locales: necesita los nativos, así que se comprueba en la `5.1.15`.

## Fase 4: Device y Transport con FakeHandler

### 4.1 Handler y FakeHandler

Origen: `HandlerInterface.ts` y `FakeHandler.ts` de mediasoup-client 3.26 (antes `pymediasoup/handlers/handler_interface.py`, `pymediasoup/models/handler_interface.py`, `tests/fake_handler.py`). El contrato es el de mediasoup-client, coherente con la `2.4`: la factory da las capacidades nativas por dirección y crea handlers ya listos, sin `run`. El `FakeHandler` de pymediasoup hereda de `AiortcHandler` y usa la pila real, así que se porta el de mediasoup-client.

- [x] `4.1.1` `Handler`, con todos los métodos de mediasoup-client 3.26 y `CompletableFuture`, en el paquete `handler` junto a sus opciones y resultados. Incluye `pauseSending`, `resumeSending`, `receive`, `stopReceiving`, `pauseReceiving` y `resumeReceiving` por lotes, y `getDataChannelMaxMessageSize`, que la Fase 14 usará sin cambiar la interfaz. Las estadísticas son `RTCStatsReport` y `setRtpEncodingParameters` recibe `RTCRtpEncodingParameters` de webrtc-java (`0.5.5`).
- [x] `4.1.2` Eventos del handler (`@connect`, `@connectionstatechange`, `@icegatheringstatechange`, `@icecandidateerror`) como un `HandlerListener` que el transporte pasa en `HandlerOptions`; `connect` devuelve un `CompletionStage` que el handler espera. Nuevo enum `transport.IceGatheringState`.
- [x] `4.1.3` `HandlerOptions` (antes `HandlerRunOptions`): dirección, parámetros ICE, DTLS y SCTP, servidores ICE, política ICE, la función `getSendExtendedRtpCapabilities` y el listener. `additionalSettings` llega con la `10.2.8`.
- [x] `4.1.4` `HandlerSendOptions` (con `streamId`) y `HandlerSendResult`, con `@Nullable RTCRtpSender` (`1.7.2`).
- [x] `4.1.5` `HandlerReceiveOptions` (con `streamId`) y `HandlerReceiveResult`, con el track y `@Nullable RTCRtpReceiver` (`1.7.3`).
- [x] `4.1.6` `HandlerSendDataChannelOptions`, `HandlerSendDataChannelResult`, `HandlerReceiveDataChannelOptions` y `HandlerReceiveDataChannelResult`, con `handler.DataChannel`.
- [x] `4.1.7` `HandlerFactory`: `name`, `getNativeRtpCapabilities(direction)`, `getNativeSctpCapabilities` (que mediasoup-client 3.26 ya no tiene, pero `Device.sctpCapabilities` de pymediasoup necesita) y `create(options)`.
- [x] `4.1.8` `FakeHandler`: capacidades nativas fijas (`FakeParameters`) en las dos direcciones, y SCTP con `OS` y `MIS` de 1024.
- [x] `4.1.9` `FakeHandler`: `send`, `receive` y los data channels sin WebRTC, generando IDs y parámetros. Pide al transporte que conecte antes del primer envío (rol DTLS `server`) o recepción (`client`). ⚠️ no copiar: el original da al data channel un id y devuelve el siguiente; aquí son el mismo.
- [x] `4.1.10` `FakeHandler`: simular cambios de estado (`setConnectionState`, `setIceGatheringState`) y fallos (`failNextOperation`). Su factory guarda los handlers que crea, para que los tests los manejen.

### 4.2 Device

Origen: `pymediasoup/device.py` y `Device.ts` de mediasoup-client 3.26, en `jmediasoup.Device`. Es thread-safe.

- [x] `4.2.1` Constructor con `HandlerFactory`.
- [x] `4.2.2` `load`: obtener las capacidades nativas de envío y de recepción de la `HandlerFactory`.
- [x] `4.2.3` `load`: calcular las capacidades extendidas y las capacidades de recepción y de envío (`2.4`), y `canProduce` con `canSend`. `preferLocalCodecsOrder` de mediasoup-client como sobrecarga, `load(routerRtpCapabilities, preferLocalCodecsOrder)`. Si falla, el device queda sin cargar y se puede volver a cargar.
- [x] `4.2.4` `load`: capacidades SCTP, de `HandlerFactory.getNativeSctpCapabilities`.
- [x] `4.2.5` `load`: cerrar el handler temporal también si hay error: no aplica, porque la factory da las capacidades sin crear un handler (`4.1`).
- [x] `4.2.6` `load` repetido, o con otro en curso: falla con `InvalidStateException`, como mediasoup-client. ⚠️ no copiar: pymediasoup avisa y vuelve.
- [x] `4.2.7` `loaded`, `handlerName`. `handlerName` está disponible desde el principio, como en mediasoup-client.
- [x] `4.2.8` `rtpCapabilities` y `sctpCapabilities`, con `InvalidStateException` si no está cargado. También `recvRtpCapabilities` y `sendRtpCapabilities` de mediasoup-client; `rtpCapabilities` es igual que `recvRtpCapabilities`.
- [x] `4.2.9` `canProduce(MediaKind)`.
- [x] `4.2.10` `createSendTransport`: movida a `4.3.11`, porque necesita `Transport`.
- [x] `4.2.11` `createRecvTransport`: movida a `4.3.12`.
- [x] `4.2.12` `observer` con `newtransport`: movida a `4.3.13`.

### 4.3 Transport básico

Origen: `pymediasoup/transport.py`

- [x] `4.3.1` Constructor: crear el handler con `HandlerFactory.create`, pasándole un `HandlerListener` que lleve sus eventos a la cola del transporte. Recibe las opciones internas (`InternalTransportOptions` en el original, antes `1.5.10`): dirección, `HandlerFactory`, capacidades RTP de recepción, la función que calcula las extendidas de envío a partir de las nativas (`getSendExtendedRtpCapabilities` en mediasoup-client) y `canProduceByKind`. Hecho como `Transport`, sellada, con `SendTransport` y `RecvTransport` (ver Decisiones). Completa los tamaños de mensaje SCTP que no envían los servidores antiguos, como mediasoup-client. `canProduceByKind` y las capacidades de recepción llegan con `produce` (`4.4`) y `consume` (`4.5`), que son quienes las usan.
- [x] `4.3.2` Cola serializada por transporte para las operaciones de handler y la entrega de eventos (`0.5.2`). ⚠️ no copiar: el original no tiene cola (`NOTE: Mediasoup client enqueue command here`). En Java hay concurrencia real. Hecho con `SerialExecutor` y `OperationQueue` en `internal.event` (ver Decisiones); el transporte los usa desde `4.3.1`.
- [x] `4.3.3` Rechazar las operaciones pendientes de la cola al cerrar: `OperationQueue.stop`, con `InvalidStateException("closed")`. La operación en curso no se interrumpe, pero su resultado se descarta.
- [x] `4.3.4` Propiedades `id`, `closed`, `direction`, `handler`, `connectionState` y `appData`. Además `iceGatheringState` de mediasoup-client (antes `14.1.4`). `appData` sin setter.
- [x] `4.3.5` Listener `connect`, obligatorio al crear el transporte, con futuro: interfaz `ConnectHandler`, llamada con `AsyncHandlers.run`. ⚠️ no copiar: el original lanza `connect` y sigue sin esperar a que termine la señalización. Falla con `InvalidStateException` si el transporte está cerrado.
- [x] `4.3.6` Listener `connectionstatechange`. Además `icegatheringstatechange` e `icecandidateerror` de mediasoup-client. Un estado repetido no se emite, y después de `close` no se emite nada.
- [x] `4.3.7` Listener `produce`, obligatorio al crear el transporte de envío, que devuelve el ID del producer: interfaz `ProduceHandler` con un record de petición, llamada con `AsyncHandlers.call`. Un ID vacío también falla. Hecho: `ProduceHandler` y `ProduceRequest`; la llamada y la comprobación del ID van con `produce` en la `4.4`.
- [x] `4.3.8` `close`: cerrar el handler y avisar a todos los productores y consumidores. Para la cola, cierra el handler y pone el estado en `closed` sin evento. El aviso a productores y consumidores llega con `4.4.3` y `4.5.3`.
- [x] `4.3.9` `observer` con `close`, `newproducer` y `newconsumer`. `TransportObserver` con `onClose`; `newproducer` y `newconsumer` llegan con la `4.4` y la `4.5`.
- [x] `4.3.10` `additionalSettings`: movida a `10.2.8`.
- [x] `4.3.11` `Device.createSendTransport` (antes `4.2.10`).
- [x] `4.3.12` `Device.createRecvTransport` (antes `4.2.11`). ⚠️ no copiar: el original solo acepta `dict` en recv. Las dos variantes deben aceptar los mismos tipos de entrada. Las dos reciben `TransportOptions`.
- [x] `4.3.13` `Device.observer` con `newtransport` (antes `4.2.12`). ⚠️ no copiar: el original lo documenta pero nunca lo emite. `DeviceObserver.onNewTransport`, emitido en el ejecutor del transporte nuevo antes que cualquier otro evento suyo.

### 4.4 Producer básico y Transport.produce

Origen: `pymediasoup/producer.py`, `pymediasoup/transport.py`

- [x] `4.4.1` `Producer`: propiedades `id`, `localId`, `closed`, `kind`, `rtpSender`, `track`, `rtpParameters`, `paused`, `maxSpatialLayer` y `appData`. `paused` empieza a `true` si el track está deshabilitado y `disableTrackOnPause` está activo, como en mediasoup-client; `paused` y `maxSpatialLayer` cambian desde las fases 8 y 11. `appData` sin setter, como en el transporte.
- [x] `4.4.2` `Producer.close`. El observer `close` se emite en el ejecutor del transporte.
- [x] `4.4.3` `Producer.transportClosed` y evento `transportclose` (`onTransportClose`). Se entrega antes que el observer `close` del transporte.
- [x] `4.4.4` `Transport.produce`: validaciones de track, dirección y `canProduce`. ⚠️ no copiar: el original no comprueba si el transporte está cerrado. En `SendTransport.produce`, así que la dirección la garantiza el tipo. Fallan en el futuro: `InvalidStateException` si está cerrado o el track ha terminado, `UnsupportedException` si no se puede enviar ese tipo.
- [x] `4.4.5` `createSendTransport` exige los listeners `connect` y `produce`, y `createRecvTransport` solo `connect` (`0.5.3`). ⚠️ no copiar: la segunda comprobación del original en `produce` mira `connect`. Hecho en la `4.3`: los handlers son parámetros obligatorios.
- [x] `4.4.6` `Transport.produce`: flujo con handler y listener, y registro del producer. Como mediasoup-client: normaliza los encodings, llama a `handler.send` en la cola y al `ProduceHandler` en el ejecutor, y si falla hace `stopSending`. Un ID vacío falla. Si el transporte se cierra mientras el servidor crea el producer, falla con `InvalidStateException` y no lo registra. No se porta `validateAndNormalizeRtpParameters`: los records ya validan, y los valores por defecto los pone el servidor.
- [x] `4.4.7` Si falla con el transporte cerrado, parar el track (`TODO` pendiente en el original): movida a `8.2.12`, con el resto de `stopTracks`.
- [x] `4.4.8` Conectar `@close` del producer con `handler.stopSending`, en la cola; un fallo solo se registra.
- [x] `4.4.9` `Producer.observer` con `close`. Además, el observer `newproducer` del transporte.

### 4.5 Consumer básico y Transport.consume

Origen: `pymediasoup/consumer.py`, `pymediasoup/transport.py`

- [x] `4.5.1` `Consumer`: propiedades `id`, `localId`, `producerId`, `closed`, `kind`, `rtpReceiver`, `track`, `rtpParameters`, `paused` y `appData`. `kind` sale de `ConsumerOptions` y `paused` empieza a `true` si el track está deshabilitado, como en mediasoup-client. `appData` sin setter.
- [x] `4.5.2` `Consumer.close`. El observer `close` se emite en el ejecutor del transporte.
- [x] `4.5.3` `Consumer.transportClosed` y evento `transportclose` (`onTransportClose`).
- [x] `4.5.4` `Transport.consume`: validaciones y `canReceive`. En `RecvTransport.consume`; los campos los valida `ConsumerOptions`. Fallan en el futuro: `InvalidStateException` si está cerrado, `UnsupportedException("cannot consume this Producer")` si `canReceive` no lo admite.
- [x] `4.5.5` `Transport.consume`: flujo con handler y registro del consumer. ⚠️ no copiar: el original no pasa `rtpReceiver` al `Consumer`. Por lotes, como mediasoup-client 3.26 (antes `14.1.1`): los `consume` que llegan mientras hay otra operación en curso se negocian juntos en un solo `handler.receive`. Si el lote falla, fallan todos sus `consume`. Al cerrar el transporte fallan los pendientes y los que el handler está recibiendo, sin esperarle. El probator va en la `8.3`.
- [x] `4.5.6` Conectar `@close` del consumer con `handler.stopReceiving`. También por lotes (`closePendingConsumers`); un fallo solo se registra.
- [x] `4.5.7` `Consumer.observer` con `close`. Además, el observer `newconsumer` del transporte.

### 4.6 Port de la batería de tests

Origen: `tests/__init__.py`, y `src/test/test.ts` de mediasoup-client, cuyo `FakeHandler` se porta. Los tests de `test.ts` que tocan funciones de fases posteriores (pausa, `replaceTrack`, stats, ICE, data channels) van con ellas. Los que comprueban tipos de argumento o la dirección del transporte no aplican: lo garantizan los records y los tipos `SendTransport` y `RecvTransport`.

- [x] `4.6.1` `test_create_device`: `DeviceTest.aNewDeviceIsNotLoaded`.
- [x] `4.6.2` `test_device_load`: `DeviceTest.loadSucceeds` y los casos de error.
- [x] `4.6.3` `test_device_rtp_capabilities`: `DeviceTest.loadSucceeds`.
- [x] `4.6.4` `test_device_sctp_capabilities`: `DeviceTest.loadSucceeds`.
- [x] `4.6.5` `test_device_can_produce_audio` y `test_device_can_produce_video`: `DeviceTest.canProduceAudioAndVideo`.
- [x] `4.6.6` `test_send_transport`: `TransportTest.createSendTransportSucceeds`.
- [x] `4.6.7` `test_recv_transport`: `TransportTest.createRecvTransportSucceeds`. La parte de `produce` en un transporte de recepción no aplica: `RecvTransport` no tiene `produce`.
- [x] `4.6.8` `test_produce`, sin la parte de data channels: `ProducerTest`, con los parámetros RTP esperados en `theRtpParametersAreThoseOfPymediasoup`. Sin los valores por defecto que rellena pydantic (`parameter` vacío en `rtcpFeedback`), que siguen `null` (`1.3`).
- [x] `4.6.9` `test_consume`, sin la parte de data channels: `ConsumerTest`, con los parámetros RTP esperados en `theRtpParametersAreThoseOfPymediasoup`, también sin los valores por defecto de pydantic (`encrypt`).
- [x] `4.6.10` Tests de operaciones concurrentes sobre la cola: `OperationQueueTest`, `SerialExecutorTest`, `TransportTest.listenersRunOneAtATimeOnTheExecutor`, el orden de `produce` y los lotes de `consume`, y `produce` y `consume` desde 20 hilos a la vez.
- [x] `4.6.11` Tests de cierre con operaciones en curso: cerrar mientras el servidor crea un producer, mientras el handler recibe, con `consume` pendientes y con 20 `produce` en marcha, sin que ninguno quede colgado.

**Aceptación de la Fase 4** (cumplida)

- Los tests portados pasan sin cargar nativos.
- Las operaciones concurrentes sobre un transporte se ejecutan en orden.

## Fase 5: WebRtcJavaHandler, recepción

Para probar contra un servidor real hacen falta las secciones 7.1 y 7.2, así que deben adelantarse antes de cerrar esta fase.

### 5.1 Base del handler

Origen: `pymediasoup/handlers/aiortc_handler.py`

- [ ] `5.1.1` Añadir `webrtc-java` como `api`. Los nativos solo para los tests: en uso real los declara el usuario (`0.5.7`).
- [ ] `5.1.2` Gestión compartida de `PeerConnectionFactory`, con su ciclo de vida y su cierre.
- [ ] `5.1.3` Adaptadores de las interfaces de la sección 1.7 sobre webrtc-java.
- [ ] `5.1.4` Convertir en futuros los callbacks `createOffer`, `createAnswer`, `setLocalDescription` y `setRemoteDescription`.
- [ ] `5.1.5` La `HandlerFactory` de `WebRtcJavaHandler`, con `name`.
- [ ] `5.1.6` `getNativeRtpCapabilities` por dirección (`sendonly` o `recvonly`), con peer connection temporal (según `0.3.17`).
- [ ] `5.1.7` `getNativeSctpCapabilities` (`OS` y `MIS` de 1024).
- [ ] `5.1.8` Constructor: `RemoteSdp` y parámetros de envío por tipo.
- [ ] `5.1.9` Constructor: configuración de la peer connection con `iceServers` y `iceTransportPolicy`.
- [ ] `5.1.10` Mapear el estado ICE a `ConnectionState`.
- [ ] `5.1.11` `close`, liberando los recursos nativos.
- [ ] `5.1.12` `setupTransport`: extraer DTLS, fijar el rol, avisar por `@connect` y esperar el resultado.
- [ ] `5.1.13` `assertSendDirection` y `assertRecvDirection`.
- [ ] `5.1.14` Test con `Device.load` real: las capacidades incluyen Opus y VP8.
- [ ] `5.1.15` Comprobar que webrtc-java acepta un SDP generado por el writer de la `3.2` (antes `3.2.8`), y las ofertas y respuestas de `RemoteSdp` en peer connections locales (aceptación de la Fase 3), con el fingerprint `sha-512` que elige `RemoteSdp`.

### 5.2 Recibir

- [ ] `5.2.1` `receive`: asignar `localId`. ⚠️ no copiar: `str(len(map))` puede colisionar tras un `stopReceiving`. Usar un contador, como mediasoup-client.
- [ ] `5.2.2` `receive`: oferta remota con `RemoteSdp.receive`.
- [ ] `5.2.3` `receive`: `setRemoteDescription` y `createAnswer`.
- [ ] `5.2.4` `receive`: `setupTransport` con rol `client` la primera vez.
- [ ] `5.2.5` `receive`: `setLocalDescription` y localizar el transceiver por `mid`.
- [ ] `5.2.6` `applyCodecParameters` para Opus. ⚠️ no copiar: el original busca `rmtp` en vez de `fmtp`.
- [ ] `5.2.7` Devolver track y receiver envueltos en las abstracciones.
- [ ] `5.2.8` `stopReceiving`: cerrar la sección y renegociar.
- [ ] `5.2.9` Test E2E: recibir audio de un producer del servidor.
- [ ] `5.2.10` Test E2E: recibir vídeo de un producer del servidor.
- [ ] `5.2.11` Test: `connectionState` llega a `connected` y a `closed`.
- [ ] `5.2.12` `receive`: usar `streamId` de `ConsumerOptions` (o el `producerId` si falta) como stream del `msid`, como mediasoup-client.

**Aceptación de la Fase 5**

- Los sinks reciben audio y vídeo reales desde el servidor mediasoup.
- Al cerrar no quedan hilos ni recursos nativos vivos.

## Fase 6: WebRtcJavaHandler, envío

### 6.1 Fuentes de prueba

Origen: `AudioStreamTrack` y `VideoStreamTrack` de aiortc, usados en `examples/mediasoup.py`

- [ ] `6.1.1` Fuente de audio de silencio sobre `CustomAudioSource`.
- [ ] `6.1.2` Fuente de vídeo con frames de color fijo sobre `CustomVideoSource`.
- [ ] `6.1.3` Temporización estable de las dos fuentes.
- [ ] `6.1.4` Parada de las fuentes al cerrar.

### 6.2 Enviar

- [ ] `6.2.1` `send`: copiar y reducir los parámetros RTP según el codec.
- [ ] `6.2.2` `send`: `addTransceiver` en `sendonly`.
- [ ] `6.2.3` `send`: `createOffer` y `setupTransport` con rol `server` la primera vez.
- [ ] `6.2.4` `send`: `setLocalDescription` y obtener el `mid`.
- [ ] `6.2.5` `send`: localizar la sección de la oferta por `mid` en vez de confiar solo en el índice.
- [ ] `6.2.6` `send`: CNAME y encodings desde el SDP local.
- [ ] `6.2.7` `send`: `RemoteSdp.send` y `setRemoteDescription`.
- [ ] `6.2.8` `send`: reutilizar el `mid` de una sección cerrada.
- [ ] `6.2.9` `stopSending` real. ⚠️ no copiar: en el original no hace nada porque aiortc no tiene `removeTrack`.
- [ ] `6.2.10` Test E2E: enviar audio y comprobar en el servidor que llega RTP.
- [ ] `6.2.11` Test E2E: enviar vídeo y comprobar en el servidor que llega RTP.
- [ ] `6.2.12` Test E2E: cerrar un producer y crear otro en el mismo transporte.
- [ ] `6.2.13` `send`: usar `streamId` de `ProducerOptions` como stream del `msid`, para sincronizar los tracks que lo comparten.
- [ ] `6.2.14` `send`: calcular las capacidades extendidas de envío con las capacidades locales de la oferta, como mediasoup-client, y no con las de `load`.

**Aceptación de la Fase 6**

- El servidor recibe RTP de audio y vídeo desde jmediasoup.
- Producers y consumers se pueden crear y cerrar repetidamente en el mismo transporte.

## Fase 7: hito MVP

### 7.1 Entorno de integración

- [ ] `7.1.1` Añadir Testcontainers al catálogo de versiones.
- [ ] `7.1.2` Crear un source set o tarea `integrationTest`, separado de `test`.
- [ ] `7.1.3` Hacer que la tarea sea compatible con el configuration cache.
- [ ] `7.1.4` Arrancar la imagen elegida en `0.4.7` por digest, y esperar a que esté lista.
- [ ] `7.1.5` Configurar la red y `announcedAddress` según `0.4.8` y `0.4.9`.
- [ ] `7.1.6` Omitir los tests con un mensaje claro si Docker no está disponible.
- [ ] `7.1.7` Recoger los logs del contenedor si falla un test.

### 7.2 Señalización de los tests

Este cliente es solo para los tests y se adapta a la señalización de la imagen elegida.

- [ ] `7.2.1` Cliente WebSocket o HTTP según la imagen.
- [ ] `7.2.2` Obtener las capacidades RTP del router.
- [ ] `7.2.3` Crear transportes WebRTC.
- [ ] `7.2.4` Conectar transportes (`connect`).
- [ ] `7.2.5` Crear un producer en el servidor (`produce`).
- [ ] `7.2.6` Crear y reanudar un consumer en el servidor.
- [ ] `7.2.7` Consultar las estadísticas del servidor para comprobar que llega RTP.

### 7.3 Test E2E de loopback

- [ ] `7.3.1` Cargar `Device` con las capacidades del router.
- [ ] `7.3.2` Crear transportes send y recv.
- [ ] `7.3.3` Producir audio y vídeo.
- [ ] `7.3.4` Consumir lo producido en el mismo proceso.
- [ ] `7.3.5` Comprobar que el sink de audio recibe muestras.
- [ ] `7.3.6` Comprobar que el sink de vídeo recibe frames.
- [ ] `7.3.7` Comprobar las transiciones de `connectionState`.
- [ ] `7.3.8` Cerrar todo y comprobar el estado final.
- [ ] `7.3.9` Ejecutar el test varias veces seguidas para detectar inestabilidad.

### 7.4 Entrega del MVP

- [ ] `7.4.1` Ejecutar `integrationTest` en CI.
- [ ] `7.4.2` Ejemplo mínimo ejecutable.
- [ ] `7.4.3` Sección de uso en el README, con plataformas soportadas y limitaciones.
- [ ] `7.4.4` Calcular la versión desde git con `gradle-git-versioning`: una etiqueta `vX.Y.Z` da `X.Y.Z`, y cualquier otro commit la siguiente versión menor con `-SNAPSHOT`. Comprobar que funciona con el configuration cache. Sustituye a la versión fija de `0.2.2`.
- [ ] `7.4.5` Generar los jars de fuentes y Javadoc.
- [ ] `7.4.6` Completar los metadatos del POM (nombre, URL, licencia, desarrolladores, SCM), e incluir `LICENSE` y `NOTICE` en `META-INF/` de los jars, que hoy no los llevan. MIT e ISC exigen que el aviso y la licencia de los originales acompañen a cada copia.
- [ ] `7.4.7` Firma GPG en el build.
- [ ] `7.4.8` Publicar en Central Portal desde el build.
- [ ] `7.4.9` Workflow de publicación al empujar una etiqueta `vX.Y.Z`.
- [ ] `7.4.10` Publicar la versión `0.1.0` en Maven Central y probarla desde un proyecto limpio.

**Aceptación del MVP**

- El test de loopback pasa en CI de forma estable.
- Cumple la [definición de MVP](#resumen).
- Un proyecto nuevo puede usar la `0.1.0` desde Maven Central.

---

# Parte B: del MVP a la paridad

## Fase 8: control de medios

### 8.1 Pausa y reanudación

Origen: `producer.py`, `consumer.py`

- [ ] `8.1.1` `Producer.pause`: deshabilitar el track. ⚠️ no copiar: en el original no lo hace, porque el track de aiortc no tiene `enabled`.
- [ ] `8.1.2` `Producer.resume`.
- [ ] `8.1.3` `disableTrackOnPause`.
- [ ] `8.1.4` `zeroRtpOnPause`: sin track al pausar, con track al reanudar. ⚠️ no copiar: el original emite `@replacetrack` sin argumento.
- [ ] `8.1.5` `Producer.observer` con `pause` y `resume`.
- [ ] `8.1.6` `Consumer.pause`. ⚠️ no copiar: en el original `_disableTrackOnPause` es siempre `false` y no hace nada.
- [ ] `8.1.7` `Consumer.resume`.
- [ ] `8.1.8` `Consumer.observer` con `pause` y `resume`.
- [ ] `8.1.9` Test E2E: el servidor deja de recibir RTP al pausar con `zeroRtpOnPause`.

### 8.2 Sustitución y fin de track

- [ ] `8.2.1` `Producer.replaceTrack`: validaciones y mismo track.
- [ ] `8.2.2` `Producer.replaceTrack`: permitir un track nulo, como indica el comentario del original.
- [ ] `8.2.3` `Producer.replaceTrack`: parar el track anterior según `stopTracks`.
- [ ] `8.2.4` `Producer.replaceTrack`: aplicar el estado de pausa al track nuevo.
- [ ] `8.2.5` Si el producer está cerrado, parar el track recibido según `stopTracks`.
- [ ] `8.2.6` `handler.replaceTrack`.
- [ ] `8.2.7` Conectar `@replacetrack` del producer con el handler.
- [ ] `8.2.8` `stopTracks` al cerrar.
- [ ] `8.2.9` Evento y observer `trackended` en `Producer`.
- [ ] `8.2.10` Evento y observer `trackended` en `Consumer`.
- [ ] `8.2.11` Retirar el listener del track al destruirlo.
- [ ] `8.2.12` Si `produce` falla con el transporte cerrado, parar el track según `stopTracks` (antes `4.4.7`).
- [ ] `8.2.13` Decidir qué es parar un track en webrtc-java, que no tiene `stop()` (`0.3.10`): `dispose()`, que libera el track nativo, o no hacer nada y dejar el track al usuario. Comprobar antes con los nativos qué pasa si se llama dos veces a `dispose()`. Afecta a `8.2.3`, `8.2.5`, `8.2.8` y `8.2.12`.

### 8.3 Probator RTP y reciclado

- [ ] `8.3.1` Crear el consumer de probation con el primer consumer de vídeo.
- [ ] `8.3.2` Comprobar que no se crea más de una vez, también con llamadas concurrentes.
- [ ] `8.3.3` Decidir si un fallo del probator debe hacer fallar `consume`. mediasoup-client solo registra el error.
- [ ] `8.3.4` Test E2E de reciclado de secciones: crear y cerrar consumers repetidamente.
- [ ] `8.3.5` Test E2E de reciclado de una sección de audio con una de vídeo.
- [ ] `8.3.6` Test E2E: cerrar el consumer de la primera sección (se deshabilita, no se cierra).
- [ ] `8.3.7` Publicar la versión `0.2.0`.

**Aceptación de la Fase 8**

- `pause` y `resume` afectan al medio de verdad, comprobado desde el servidor.
- `replaceTrack` no interrumpe la sesión.

## Fase 9: data channels

### 9.1 DataProducer

Origen: `pymediasoup/data_producer.py`

- [ ] `9.1.1` Propiedades `id`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol` y `appData`.
- [ ] `9.1.2` `bufferedAmount` y `bufferedAmountLowThreshold` (lectura y escritura).
- [ ] `9.1.3` `send` de texto.
- [ ] `9.1.4` `send` binario.
- [ ] `9.1.5` `close` y `transportClosed`.
- [ ] `9.1.6` Eventos `open`, `error`, `bufferedamountlow` y `transportclose`.
- [ ] `9.1.7` Cierre iniciado por el canal: marcar como cerrado y avisar.
- [ ] `9.1.8` Descartar y registrar los mensajes entrantes.
- [ ] `9.1.9` `observer` con `close`.

### 9.2 DataConsumer

Origen: `pymediasoup/data_consumer.py`

- [ ] `9.2.1` Propiedades `id`, `dataProducerId`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol` y `appData`.
- [ ] `9.2.2` `binaryType` (lectura y escritura), o una alternativa idiomática en Java. `DataChannelMessage` ya ofrece bytes y texto, así que probablemente baste con documentar que no hace falta.
- [ ] `9.2.3` `close` y `transportClosed`.
- [ ] `9.2.4` Eventos `open`, `error`, `message` y `transportclose`.
- [ ] `9.2.5` Cierre iniciado por el canal.
- [ ] `9.2.6` `observer` con `close`.

### 9.3 Transport

Origen: `pymediasoup/transport.py`

- [ ] `9.3.1` Listener `producedata`, opcional al crear el transporte de envío porque no todos usan data channels: sobrecarga `createSendTransport(options, connect, produce, produceData)`; sin ella, `produceData` falla con `InvalidStateException`.
- [ ] `9.3.2` `produceData`: validaciones (dirección, SCTP activo, listener `producedata` presente).
- [ ] `9.3.3` `produceData`: `ordered = false` si hay `maxPacketLifeTime` o `maxRetransmits`, y `true` si no hay ninguno (`validateAndNormalizeSctpStreamParameters` de mediasoup-client). ⚠️ no copiar: el original comprueba si son truthy, así que un valor 0 se ignora.
- [ ] `9.3.4` `produceData`: flujo con handler y registro.
- [ ] `9.3.5` `consumeData`: validaciones.
- [ ] `9.3.6` `consumeData`: flujo con handler y registro.
- [ ] `9.3.7` Conectar `@close` de los dos tipos con el transporte.
- [ ] `9.3.8` Avisar a `DataProducer` y `DataConsumer` al cerrar el transporte.
- [ ] `9.3.9` `observer` con `newdataproducer` y `newdataconsumer`.

### 9.4 Handler y SDP

Origen: `aiortc_handler.py`, `media_section.py`, `remote_sdp.py`

- [ ] `9.4.1` `sendDataChannel`: asignar el `streamId` con contador y módulo de `MIS`. ⚠️ no copiar: el original ignora el `streamId` recibido.
- [ ] `9.4.2` `sendDataChannel`: crear el canal `negotiated`. ⚠️ no copiar: el original no pasa `maxRetransmits`.
- [ ] `9.4.3` `sendDataChannel`: negociar `m=application` la primera vez.
- [ ] `9.4.4` `receiveDataChannel`: crear el canal `negotiated`.
- [ ] `9.4.5` `receiveDataChannel`: negociar `m=application` la primera vez.
- [ ] `9.4.6` `AnswerMediaSection`: sección `application` con `sctp-port`.
- [ ] `9.4.7` `AnswerMediaSection`: `sctpmap` antiguo.
- [ ] `9.4.8` `OfferMediaSection`: sección `application` con `sctp-port`.
- [ ] `9.4.9` `OfferMediaSection`: `sctpmap` antiguo (`oldDataChannelSpec`).
- [ ] `9.4.10` `RemoteSdp.sendSctpAssociation`.
- [ ] `9.4.11` `RemoteSdp.receiveSctpAssociation`.
- [ ] `9.4.12` Implementación de `handler.DataChannel` sobre `RTCDataChannel`: copiar cada `RTCDataChannelBuffer` en un `DataChannelMessage`, traducir `onStateChange` a `onOpen` y `onClose`, y calcular `onBufferedAmountLow` con el umbral, que webrtc-java no tiene.

### 9.5 Tests

- [ ] `9.5.1` Parte de data channels de `test_produce` y `test_consume`, con `FakeHandler`.
- [ ] `9.5.2` Test E2E: enviar y recibir texto a través del servidor.
- [ ] `9.5.3` Test E2E: enviar y recibir binario.
- [ ] `9.5.4` Test E2E: canal no ordenado con `maxRetransmits`.
- [ ] `9.5.5` Test: `bufferedamountlow` se dispara.
- [ ] `9.5.6` Test: cerrar el transporte cierra los canales.
- [ ] `9.5.7` Publicar la versión `0.3.0`.

**Aceptación de la Fase 9**

- Mensajes de texto y binarios llegan de extremo a extremo.
- Los parámetros de fiabilidad llegan al canal nativo.

## Fase 10: stats e ICE

### 10.1 Estadísticas

Origen: `transport.py`, `producer.py`, `consumer.py`, `aiortc_handler.py`

- [ ] `10.1.1` `getStats` devuelve el `RTCStatsReport` de webrtc-java (`0.5.5`), y uno vacío o construido a mano con `FakeHandler`.
- [ ] `10.1.2` `handler.getTransportStats`.
- [ ] `10.1.3` `handler.getSenderStats`.
- [ ] `10.1.4` `handler.getReceiverStats`.
- [ ] `10.1.5` `Transport.getStats`.
- [ ] `10.1.6` `Producer.getStats`. ⚠️ no copiar: el original devuelve la lista de `emit_for_results` y, con el transporte cerrado, devuelve la excepción en vez de lanzarla.
- [ ] `10.1.7` `Consumer.getStats`, con el mismo cuidado.
- [ ] `10.1.8` Tests E2E de los tres niveles.

### 10.2 ICE

Origen: `transport.py`, `aiortc_handler.py`, `remote_sdp.py`

- [ ] `10.2.1` `Transport.restartIce`.
- [ ] `10.2.2` `handler.restartIce` en send, con `RTCOfferOptions.iceRestart`. ⚠️ no copiar: el original no puede pedirlo porque aiortc no tiene esa opción.
- [ ] `10.2.3` `handler.restartIce` en recv.
- [ ] `10.2.4` `restartIce` antes de que el transporte esté listo: solo actualiza los parámetros.
- [ ] `10.2.5` `Transport.updateIceServers`.
- [ ] `10.2.6` `handler.updateIceServers` con `setConfiguration`. ⚠️ no copiar: en el original solo registra un aviso.
- [ ] `10.2.7` `iceTransportPolicy` (`all` y `relay`).
- [ ] `10.2.8` `additionalSettings` y `proprietaryConstraints`: decidir cuáles tienen sentido en webrtc-java y añadirlos a `TransportOptions` con su builder, sin campos reservados por jmediasoup. ⚠️ no copiar: el original llama a `copy(deep=True)` sobre un `dict`.
- [ ] `10.2.9` `RemoteSdp.updateIceParameters`: quitar `icelite` si ya no aplica.
- [ ] `10.2.10` Test E2E: reiniciar ICE y seguir recibiendo medios.
- [ ] `10.2.11` Test E2E: conexión a través de un TURN, si la imagen o el entorno lo permiten.
- [ ] `10.2.12` Publicar la versión `0.4.0`.

**Aceptación de la Fase 10**

- `restartIce` restablece el medio en una sesión activa.
- Los tres niveles de `getStats` devuelven datos reales.

## Fase 11: codecs y encodings

### 11.1 Opciones de codec

Origen: `media_section.py`

- [ ] `11.1.0` Decidir cómo devuelve `RemoteAnswerMediaSection` los cambios que las opciones de Opus hacen en los parámetros de la oferta (`sprop-stereo`, `useinbandfec`, `usedtx`, `ptime` y el NACK de `opusNack`): mediasoup-client modifica el objeto que recibe, y aquí los modelos son inmutables.
- [ ] `11.1.1` `opusStereo`.
- [ ] `11.1.2` `opusFec`.
- [ ] `11.1.3` `opusDtx`.
- [ ] `11.1.4` `opusMaxPlaybackRate`.
- [ ] `11.1.5` `opusMaxAverageBitrate`.
- [ ] `11.1.6` `opusPtime`.
- [ ] `11.1.7` `videoGoogleStartBitrate`.
- [ ] `11.1.8` `videoGoogleMaxBitrate`.
- [ ] `11.1.9` `videoGoogleMinBitrate`.
- [ ] `11.1.10` Comprobar en el SDP de respuesta que cada opción se aplica.
- [ ] `11.1.11` `applyCodecParameters` con `sprop-stereo`.
- [ ] `11.1.12` `opusNack`, de mediasoup-client.

### 11.2 Selección de codec y encodings

Origen: `aiortc_handler.py`, `unified_plan_utils.py`

- [ ] `11.2.1` `produce` con `codec`: enviar el codec elegido.
- [ ] `11.2.2` Asignar `rid` `r0`, `r1`... a los encodings.
- [ ] `11.2.3` Un solo encoding: fusionarlo con el que se obtiene del SDP.
- [ ] `11.2.4` Varios encodings: usarlos tal cual.
- [ ] `11.2.5` Pasar `dtx`, `maxBitrate`, `maxFramerate`, `scaleResolutionDownBy`, `priority` y `networkPriority` al encoding nativo.
- [ ] `11.2.6` Test E2E por codec disponible: Opus, VP8, VP9 y H264.

### 11.3 Simulcast

Origen: `aiortc_handler.py`, `media_section.py`. aiortc no soporta simulcast, así que hay que apoyarse en mediasoup-client (`Chrome111.ts`).

- [ ] `11.3.1` Pasar `sendEncodings` en `addTransceiver`.
- [ ] `11.3.2` `AnswerMediaSection`: `simulcast` y `rids`.
- [ ] `11.3.3` `AnswerMediaSection`: `simulcast_03` del borrador.
- [ ] `11.3.4` Añadir `S1T3` a cada encoding con VP8 o H264. Revisar si `numTemporalLayers` o `scalabilityMode` debe aplicarse al encoding nativo.
- [ ] `11.3.5` Test E2E: el servidor recibe varias capas.
- [ ] `11.3.6` Test E2E: el consumer recibe capas distintas al cambiar la capa preferida en el servidor.

### 11.4 SVC

- [ ] `11.4.1` Detectar VP9 SVC con un encoding y varias capas espaciales.
- [ ] `11.4.2` `addLegacySimulcast`. ⚠️ no copiar: tiene varios bugs (`.get` sobre una lista, clave `ssrc` en vez de `ssrcs`, `join` de enteros).
- [ ] `11.4.3` Rama VP9 de `send`. ⚠️ no copiar: asigna `sdp_transform.parse` sin llamarlo.
- [ ] `11.4.4` Revisar en mediasoup-client si todavía hace falta este truco con `scalabilityMode` nativo.
- [ ] `11.4.5` Test E2E con VP9 SVC.

### 11.5 Control en caliente

Origen: `producer.py`, `aiortc_handler.py`

- [ ] `11.5.1` `Producer.setMaxSpatialLayer`. ⚠️ no copiar: usa `self._kind`, que no existe.
- [ ] `11.5.2` `handler.setMaxSpatialLayer` con `getParameters` y `setParameters`. ⚠️ no copiar: en el original solo registra un aviso.
- [ ] `11.5.3` `Producer.setRtpEncodingParameters`.
- [ ] `11.5.4` `handler.setRtpEncodingParameters`. ⚠️ no copiar: en el original solo registra un aviso.
- [ ] `11.5.5` Conectar `@setmaxspatiallayer` y `@setrtpencodingparameters` con el handler.
- [ ] `11.5.6` Test E2E: al limitar la capa, el servidor deja de recibir las superiores.
- [ ] `11.5.7` Publicar la versión `0.5.0`.

**Aceptación de la Fase 11**

- El simulcast funciona de extremo a extremo con VP8.
- Las opciones de codec se reflejan en el SDP y, cuando es posible, en el medio.

## Fase 12: ejemplos y utilidades de media

### 12.1 Utilidades de media

Origen: `aiortc.contrib.media`, usado en `examples/mediasoup.py`

- [ ] `12.1.1` `MediaBlackhole`: consumir tracks y descartar los datos.
- [ ] `12.1.2` `MediaRecorder`: guardar audio en WAV.
- [ ] `12.1.3` `MediaRecorder`: guardar vídeo. Decidir el formato y la dependencia.
- [ ] `12.1.4` `MediaPlayer`: reproducir audio desde un fichero.
- [ ] `12.1.5` `MediaPlayer`: reproducir vídeo desde un fichero. Decidir la dependencia.
- [ ] `12.1.6` Decidir si van en un artefacto aparte para no añadir dependencias a la librería.

### 12.2 Port del ejemplo

Origen: `examples/mediasoup.py`

- [ ] `12.2.1` Crear el subproyecto `examples`.
- [ ] `12.2.2` Conexión WebSocket con `java.net.http`.
- [ ] `12.2.3` Peticiones y respuestas (`_send_request`, `_wait_for`, `recv_msg_task`).
- [ ] `12.2.4` Parámetros de línea de comandos.
- [ ] `12.2.5` `load`, con `getRouterRtpCapabilities`.
- [ ] `12.2.6` `createSendTransport`, con `createWebRtcTransport` y `connectWebRtcTransport`.
- [ ] `12.2.7` Listeners de `produce` y `producedata`.
- [ ] `12.2.8` `join`.
- [ ] `12.2.9` `produce` de audio y vídeo.
- [ ] `12.2.10` `produceData`.
- [ ] `12.2.11` `createRecvTransport`.
- [ ] `12.2.12` Atender `newConsumer` con `consume`.
- [ ] `12.2.13` Atender `newDataConsumer` con `consumeData`.
- [ ] `12.2.14` Cierre ordenado.
- [ ] `12.2.15` README del ejemplo, indicando contra qué servidor se ejecuta.
- [ ] `12.2.16` Publicar la versión `0.6.0`.

**Aceptación de la Fase 12**

- El ejemplo intercambia audio, vídeo y datos con el servidor para el que está escrito.

## Fase 13: publicación

### 13.1 Documentación

- [ ] `13.1.1` Javadoc de toda la API pública.
- [ ] `13.1.2` README completo: instalación, uso y ejemplos.
- [ ] `13.1.3` Guía de uso de nativos por plataforma, con la dependencia para Gradle y para Maven (`0.5.7`).
- [ ] `13.1.4` Tabla de diferencias con pymediasoup y mediasoup-client.
- [ ] `13.1.5` Revisar que `LICENSE` y `NOTICE` están completos.
- [ ] `13.1.6` `CHANGELOG.md`.
- [ ] `13.1.7` `CONTRIBUTING.md`.

### 13.2 CI multiplataforma

- [ ] `13.2.1` Tests unitarios en Linux, macOS y Windows.
- [ ] `13.2.2` Carga de nativos en todas las plataformas soportadas.
- [ ] `13.2.3` Tests de integración en las plataformas en que la red Docker lo permita.
- [ ] `13.2.4` Tests con una versión reciente de mediasoup además de la 3.19.14 de la imagen (`0.5.14`), construyendo el demo oficial o con otra imagen.
- [ ] `13.2.5` Prueba de fugas: crear y cerrar transportes en bucle, y vigilar la memoria nativa.
- [ ] `13.2.6` Valorar variantes de Gradle por SO y CPU para que los nativos se elijan solos (`0.5.7`).

### 13.3 Maven Central

La publicación en Central Portal se monta en la `7.4`, con la versión `0.1.0`.

- [ ] `13.3.1` Comprobar la matriz de paridad completa.
- [ ] `13.3.2` Publicar la versión `1.0.0`.

**Aceptación de la Fase 13**

- Un proyecto nuevo puede usar `io.github.hectorespert:jmediasoup` desde Maven Central y completar el flujo básico.

---

# Parte C: más allá de pymediasoup

## Fase 14: más allá de pymediasoup (opcional)

Funcionalidades de mediasoup-client (`src/Transport.ts`) que pymediasoup no tiene.

- [x] `14.1.1` Crear consumers por lotes en una sola renegociación (`createPendingConsumers`): movida a `4.5.5`, con el cierre por lotes en la `4.5.6`.
- [ ] `14.1.2` Pausar consumers por lotes (`pausePendingConsumers`).
- [ ] `14.1.3` Reanudar consumers por lotes (`resumePendingConsumers`).
- [x] `14.1.4` `Transport.iceGatheringState`: movida a `4.3.4`, con los eventos `icegatheringstatechange` e `icecandidateerror`.
- [ ] `14.1.5` `Transport.getDataChannelMaxMessageSize`.
- [ ] `14.1.6` Callbacks `onRtpSender` y `onRtpReceiver` de `ProducerOptions` y `ConsumerOptions`: decidir cómo se ejecutan con la cola del transporte.
- [ ] `14.1.7` Comparar periódicamente con mediasoup-client para detectar funcionalidades nuevas.

---

# Matriz de paridad

Cubre la API de pymediasoup. `Fase` es la fase donde queda funcional; una propiedad básica puede aparecer antes.

## Device

| Elemento | Fase |
|---|---|
| Constructor (`handlerFactory`) | 4.2 |
| `load` | 4.2 |
| `loaded`, `handlerName` | 4.2 |
| `rtpCapabilities`, `sctpCapabilities` (y `recvRtpCapabilities`, `sendRtpCapabilities`, de mediasoup-client) | 4.2 |
| `canProduce` | 4.2 |
| `createSendTransport`, `createRecvTransport` | 4.3 |
| `observer`: `newtransport` | 4.3 |

## Transport

| Elemento | Fase |
|---|---|
| `id`, `closed`, `direction`, `handler`, `connectionState`, `appData` | 4.3 |
| `close` | 4.3 |
| `produce` | 4.4 |
| `consume` | 4.5 |
| `produceData`, `consumeData` | 9.3 |
| `getStats` | 10.1 |
| `restartIce`, `updateIceServers` | 10.2 |
| Eventos `connect`, `connectionstatechange`, `produce` | 4.3 |
| Evento `producedata` | 9.3 |
| `observer`: `close`, `newproducer`, `newconsumer` | 4.3, 4.4, 4.5 |
| `observer`: `newdataproducer`, `newdataconsumer` | 9.3 |
| Probator RTP (`probatorConsumerCreated`) | 8.3 |

## Producer

| Elemento | Fase |
|---|---|
| `id`, `localId`, `closed`, `kind`, `rtpSender`, `track`, `rtpParameters`, `paused`, `maxSpatialLayer`, `appData` | 4.4 |
| `close`, `transportClosed` | 4.4 |
| `pause`, `resume` | 8.1 |
| `replaceTrack` | 8.2 |
| `getStats` | 10.1 |
| `setMaxSpatialLayer`, `setRtpEncodingParameters` | 11.5 |
| Opciones `stopTracks`, `disableTrackOnPause`, `zeroRtpOnPause` | 8.1, 8.2 |
| Eventos `transportclose`, `trackended` | 4.4, 8.2 |
| `observer`: `close`, `pause`, `resume`, `trackended` | 4.4, 8.1, 8.2 |
| Internos `@close`, `@replacetrack`, `@setmaxspatiallayer`, `@setrtpencodingparameters`, `@getstats` | 4.4, 8.2, 11.5, 10.1 |

## Consumer

| Elemento | Fase |
|---|---|
| `id`, `localId`, `producerId`, `closed`, `kind`, `rtpReceiver`, `track`, `rtpParameters`, `paused`, `appData` | 4.5 |
| `close`, `transportClosed` | 4.5 |
| `pause`, `resume` | 8.1 |
| `getStats` | 10.1 |
| Eventos `transportclose`, `trackended` | 4.5, 8.2 |
| `observer`: `close`, `pause`, `resume`, `trackended` | 4.5, 8.1, 8.2 |
| Internos `@close`, `@getstats` | 4.5, 10.1 |

## DataProducer

| Elemento | Fase |
|---|---|
| `id`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol`, `appData` | 9.1 |
| `bufferedAmount`, `bufferedAmountLowThreshold` | 9.1 |
| `send` | 9.1 |
| `close`, `transportClosed` | 9.1 |
| Eventos `open`, `error`, `bufferedamountlow`, `transportclose` | 9.1 |
| `observer`: `close` | 9.1 |

## DataConsumer

| Elemento | Fase |
|---|---|
| `id`, `dataProducerId`, `closed`, `sctpStreamParameters`, `readyState`, `label`, `protocol`, `appData` | 9.2 |
| `binaryType` | 9.2 |
| `close`, `transportClosed` | 9.2 |
| Eventos `open`, `error`, `message`, `transportclose` | 9.2 |
| `observer`: `close` | 9.2 |

## HandlerInterface (Handler) y AiortcHandler (WebRtcJavaHandler)

| Elemento | Fase |
|---|---|
| `name`, `createFactory`, `close` | 5.1 |
| `pc`, `remoteSdp` (accesores internos) | 5.1 |
| `getNativeRtpCapabilities`, `getNativeSctpCapabilities` | 5.1 |
| `run` (constructor del handler en jmediasoup) | 4.1, 5.1 |
| `_setupTransport`, `_assertSendDirection`, `_assertRecvDirection` | 5.1 |
| `receive`, `stopReceiving` | 5.2 |
| `send`, `stopSending` | 6.2 |
| `replaceTrack` | 8.2 |
| `sendDataChannel`, `receiveDataChannel` | 9.4 |
| `getTransportStats`, `getSenderStats`, `getReceiverStats` | 10.1 |
| `restartIce`, `updateIceServers` | 10.2 |
| `setMaxSpatialLayer`, `setRtpEncodingParameters` | 11.5 |
| Eventos `@connect`, `@connectionstatechange` | 4.1 |

## ortc.py

| Elemento | Fase |
|---|---|
| `RTP_PROBATOR_MID`, `RTP_PROBATOR_SSRC`, `RTP_PROBATOR_CODEC_PAYLOAD_TYPE` | 2.3 |
| `matchCodecs`, `isRtxCodec`, `reduceRtcpFeedback`, `matchHeaderExtensions` | 2.3 |
| `getExtendedRtpCapabilities`, `getRecvRtpCapabilities`, `canSend`, `canReceive` (y `getSendRtpCapabilities`, de mediasoup-client) | 2.4 |
| `getSendingRtpParameters`, `getSendingRemoteRtpParameters`, `reduceCodecs`, `generateProbatorRtpParameters` | 2.5 |

## handlers/sdp

| Elemento | Fase |
|---|---|
| `extractRtpCapabilities`, `extractDtlsParameters`, `getCname` | 3.3 |
| `applyCodecParameters` | 5.2, 11.1 |
| `getRtpEncodings` | 3.3 |
| `addLegacySimulcast` | 11.4 |
| `getCodecName`, `MediaSection` (`mid`, `closed`, `getDict`, `setIceParameters`, `disable`, `close`, `setDtlsRole`) | 3.4 |
| `AnswerMediaSection`, `OfferMediaSection`: audio y vídeo | 3.4 |
| `AnswerMediaSection`, `OfferMediaSection`: `application` | 9.4 |
| `AnswerMediaSection`: `simulcast`, `simulcast_03`, `rids` | 11.3 |
| `AnswerMediaSection`: `codecOptions` | 11.1 |
| `RemoteSdp`: `updateIceParameters`, `updateDtlsRole`, `getNextMediaSectionIdx`, `send`, `receive`, `disableMediaSection`, `closeMediaSection`, `getSdp` | 3.5 |
| `RemoteSdp`: `_addMediaSection`, `_replaceMediaSection`, `_regenerateBundleMids`, `MediaSectionIdx` | 3.5 |
| `RemoteSdp`: `sendSctpAssociation`, `receiveSctpAssociation` | 9.4 |
| `planBReceive`, `planBStopReceiving` | Excluido (Plan B) |

## Modelos y utilidades

| Elemento | Fase |
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
| `RTCIceServer` de aiortc (`IceServer`) | 1.5 |
| `ProducerCodecOptions`, `ProducerOptions`, `ConsumerOptions`, `DataProducerOptions`, `DataConsumerOptions` | 1.6 |
| `MediaStreamTrackKind` | 1.3 (`MediaKind`) |
| `HandlerRunOptions` (`HandlerOptions`), `HandlerSendOptions`, `HandlerSendResult`, `HandlerReceiveOptions`, `HandlerReceiveResult` | 4.1 |
| `HandlerSendDataChannelResult`, `HandlerReceiveDataChannelOptions`, `HandlerReceiveDataChannelResult` | 4.1 |
| `ScalabilityMode`, `parse` (internos) | 2.2 |

## Dependencias de pymediasoup y su equivalente

| Dependencia | Equivalente | Fase |
|---|---|---|
| `aiortc` | `webrtc-java` | 0.3, 5, 6 |
| `pydantic` | `record` con validación y anotaciones de Jackson | 1 |
| `pyee` | Listeners tipados | 1.2 |
| `sdp-transform` | Port propio, interno | 3.1, 3.2 |
| `h264-profile-level-id` | Port propio, interno | 2.1 |
