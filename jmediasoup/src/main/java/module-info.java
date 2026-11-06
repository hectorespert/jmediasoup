/**
 * Java client for <a href="https://mediasoup.org">mediasoup</a>.
 */
module io.github.hectorespert.jmediasoup {
    // exports io.github.hectorespert.jmediasoup is added with its first public
    // type (TODO.md 1.1.1): javac rejects exporting a package that has only a
    // package-info.java.
    requires transitive org.jspecify;
}
