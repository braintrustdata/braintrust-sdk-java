package dev.braintrust.smoketest.ddagent;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MetricExporter that collects exported metric data for test assertions.
 */
class CollectingMetricExporter implements MetricExporter {

    private final List<MetricData> metrics = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<MetricData> metricData) {
        metrics.addAll(metricData);
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

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }

    public List<MetricData> getMetrics() {
        return metrics;
    }
}
