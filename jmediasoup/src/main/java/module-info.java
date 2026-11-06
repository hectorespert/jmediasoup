/**
 * Java client for <a href="https://mediasoup.org">mediasoup</a>.
 */
module io.github.hectorespert.jmediasoup {
    requires transitive org.jspecify;
    requires org.slf4j;

    exports io.github.hectorespert.jmediasoup;
    exports io.github.hectorespert.jmediasoup.rtp;
    exports io.github.hectorespert.jmediasoup.sctp;
}
