package dev.braintrust.perf;

/**
 * Captures the result of a single performance test run.
 *
 * @param config the configuration that produced this result
 * @param payloadBytes total bytes of the OTLP HTTP request body captured at the wire
 * @param spanCount number of spans that were exported (as observed by the server)
 * @param requestCount number of HTTP requests received by the capture server
 */
public record PerfResult(PerfRunConfig config, long payloadBytes, int spanCount, int requestCount) {

    /** Bytes per span (approximate). */
    public double bytesPerSpan() {
        return spanCount > 0 ? (double) payloadBytes / spanCount : 0;
    }

    public double payloadMB() {
        return payloadBytes / (1024.0 * 1024.0);
    }

    public String summary() {
        return String.format(
                "[%s] %d request(s), %d span(s), %.3f MB total (%.1f bytes/span)",
                config.name(), requestCount, spanCount, payloadMB(), bytesPerSpan());
    }
}
