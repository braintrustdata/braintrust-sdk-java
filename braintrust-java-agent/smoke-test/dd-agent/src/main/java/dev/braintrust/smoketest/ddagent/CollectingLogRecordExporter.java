package dev.braintrust.smoketest.ddagent;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LogRecordExporter that collects exported log records for test assertions.
 */
class CollectingLogRecordExporter implements LogRecordExporter {

    private final List<LogRecordData> logRecords = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        logRecords.addAll(logs);
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

    public List<LogRecordData> getLogRecords() {
        return logRecords;
    }
}
