/**
 * Java client for <a href="https://mediasoup.org">mediasoup</a>.
 */
module io.github.hectorespert.jmediasoup {
    requires transitive com.fasterxml.jackson.annotation;
    requires transitive org.jspecify;
    requires transitive webrtc.java;
    requires org.slf4j;

    exports io.github.hectorespert.jmediasoup;
    exports io.github.hectorespert.jmediasoup.handler;
    exports io.github.hectorespert.jmediasoup.rtp;
    exports io.github.hectorespert.jmediasoup.sctp;
    exports io.github.hectorespert.jmediasoup.transport;
}
