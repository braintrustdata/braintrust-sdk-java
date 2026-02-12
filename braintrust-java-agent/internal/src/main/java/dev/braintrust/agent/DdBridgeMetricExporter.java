package dev.braintrust.agent;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetricExporter that replays OTel SDK metric data into Datadog's OTel MeterProvider shim.
 *
 * <p>Uses DELTA aggregation temporality so that exported values represent increments
 * that can be directly replayed as {@code counter.add()} or {@code histogram.record()} calls.
 *
 * <p>Supports: LONG_SUM, DOUBLE_SUM (monotonic counters), HISTOGRAM.
 * Gauges are not bridged (DD's OTel shim uses callback-based gauges which don't
 * support point-in-time value setting).
 */
class DdBridgeMetricExporter implements MetricExporter {

    private final MeterProvider ddMeterProvider;

    /** Cache of DD instruments, keyed by "scopeName/metricName". */
    private final Map<String, LongCounter> longCounters = new ConcurrentHashMap<>();
    private final Map<String, DoubleCounter> doubleCounters = new ConcurrentHashMap<>();
    private final Map<String, LongHistogram> longHistograms = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> doubleHistograms = new ConcurrentHashMap<>();

    DdBridgeMetricExporter(MeterProvider ddMeterProvider) {
        this.ddMeterProvider = ddMeterProvider;
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.DELTA;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        for (MetricData md : metrics) {
            replayMetric(md);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private void replayMetric(MetricData md) {
        String scopeName = md.getInstrumentationScopeInfo().getName();
        String metricName = md.getName();
        String key = scopeName + "/" + metricName;

        switch (md.getType()) {
            case LONG_SUM -> {
                var sum = md.getLongSumData();
                if (!sum.isMonotonic()) break; // non-monotonic sums aren't counters
                var counter = longCounters.computeIfAbsent(key, k ->
                        getMeter(scopeName, md).counterBuilder(metricName)
                                .setDescription(md.getDescription())
                                .setUnit(md.getUnit())
                                .build());
                for (LongPointData point : sum.getPoints()) {
                    counter.add(point.getValue(), point.getAttributes());
                }
            }
            case DOUBLE_SUM -> {
                var sum = md.getDoubleSumData();
                if (!sum.isMonotonic()) break;
                var counter = doubleCounters.computeIfAbsent(key, k ->
                        getMeter(scopeName, md).counterBuilder(metricName)
                                .setDescription(md.getDescription())
                                .setUnit(md.getUnit())
                                .ofDoubles()
                                .build());
                for (DoublePointData point : sum.getPoints()) {
                    counter.add(point.getValue(), point.getAttributes());
                }
            }
            case HISTOGRAM -> {
                var histogram = md.getHistogramData();
                var ddHistogram = doubleHistograms.computeIfAbsent(key, k ->
                        getMeter(scopeName, md).histogramBuilder(metricName)
                                .setDescription(md.getDescription())
                                .setUnit(md.getUnit())
                                .build());
                for (HistogramPointData point : histogram.getPoints()) {
                    // Replay each count as the mean value — this is lossy but preserves
                    // the aggregate. DD's shim will re-aggregate into its own sketch.
                    if (point.getCount() > 0 && point.hasMin() && point.hasMax()) {
                        double mean = point.getSum() / point.getCount();
                        for (long i = 0; i < point.getCount(); i++) {
                            ddHistogram.record(mean, point.getAttributes());
                        }
                    }
                }
            }
            // LONG_GAUGE, DOUBLE_GAUGE, SUMMARY, EXPONENTIAL_HISTOGRAM — not bridged
            default -> {}
        }
    }

    private Meter getMeter(String scopeName, MetricData md) {
        String version = md.getInstrumentationScopeInfo().getVersion();
        if (version != null) {
            return ddMeterProvider.meterBuilder(scopeName).setInstrumentationVersion(version).build();
        }
        return ddMeterProvider.get(scopeName);
    }
}
