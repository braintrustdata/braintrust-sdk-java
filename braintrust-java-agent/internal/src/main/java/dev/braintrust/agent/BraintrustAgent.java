package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;

import dev.braintrust.Braintrust;
import dev.braintrust.agent.instrumentation.InstrumentationInstaller;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * The real agent installation logic
 */
@Slf4j
public class BraintrustAgent {

    /**
     * Called reflectively from AgentBootstrap premain.
     */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalCallerException(
                    "Braintrust agent can only run on a braintrust classloader");
        }
        log.info("invoked on classloader: {}", BraintrustAgent.class.getClassLoader().getClass().getName());
        log.info("agentArgs: {}", agentArgs);
        log.info("Instrumentation: retransform={}", inst.isRetransformClassesSupported());
        // Fail fast if there are any issues with the Braintrust SDK
        Braintrust.get();

        InstrumentationInstaller.install(inst, BraintrustAgent.class.getClassLoader());
    }

    /**
     * Called reflectively from OtelAutoConfiguration.
     */
    public void configureOpenTelemetry(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    if (!BraintrustBridge.otelInstallCount.compareAndSet(0, 1)) {
                        log.warn("otel install invoked more than once. This should not happen. Bailing.");
                        return sdkTracerProviderBuilder;
                    }
                    var loggerBuilder = SdkLoggerProvider.builder();
                    var meterBuilder = SdkMeterProvider.builder();
                    Braintrust.get()
                            .openTelemetryEnable(
                                    sdkTracerProviderBuilder, loggerBuilder, meterBuilder);
                    return sdkTracerProviderBuilder;
                }));
        if (jvmRunningWithDatadogOtel()) {
            // FIXME DO NOT MERGE -- we need to hook up other otel stuff to the shim. logs, metrcis (is that it?)
            autoConfiguration.addTracerProviderCustomizer(
                    ((sdkTracerProviderBuilder, configProperties) -> {
                        hookUpDatadogTracerShim(sdkTracerProviderBuilder);
                        return sdkTracerProviderBuilder;
                    }));
            autoConfiguration.addMeterProviderCustomizer(
                    ((sdkMeterProviderBuilder, configProperties) -> {
                        hookUpDatadogMeterShim(sdkMeterProviderBuilder);
                        return sdkMeterProviderBuilder;
                    }));
            // NOTE: no logger shim
        }

    }

    /**
     * Checks whether the Datadog agent is present and configured for OTel integration.
     * Both conditions must be true:
     * 1. DD's bootstrap Agent class is loadable (DD agent is attached)
     * 2. dd.trace.otel.enabled=true (sys prop or DD_TRACE_OTEL_ENABLED env var)
     */
    private boolean jvmRunningWithDatadogOtel() {
        try {
            Class.forName("datadog.trace.bootstrap.Agent", false, null);
        } catch (ClassNotFoundException e) {
            return false;
        }
        // Check sys prop first, then env var
        String sysProp = System.getProperty("dd.trace.otel.enabled");
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        String envVar = System.getenv("DD_TRACE_OTEL_ENABLED");
        return Boolean.parseBoolean(envVar);
    }

    /**
     * Hooks a {@link DdBridgeSpanExporter} into the tracer provider so that OTel spans
     * are replayed into DD's OTel shim for dual visibility.
     */
    private void hookUpDatadogTracerShim(SdkTracerProviderBuilder sdkTracerProviderBuilder) {
        try {
            ClassLoader ddClassLoader = getDatadogClassLoader();
            if (ddClassLoader == null) {
                log.warn("Datadog agent classloader is null — skipping DD trace bridge.");
                return;
            }

            Class<?> otelTpClass = Class.forName(
                    "datadog.opentelemetry.shim.trace.OtelTracerProvider", true, ddClassLoader);
            TracerProvider ddTracerProvider = (TracerProvider) otelTpClass.getField("INSTANCE").get(null);

            var exporter = new DdBridgeSpanExporter(ddTracerProvider);
            sdkTracerProviderBuilder.addSpanProcessor(
                    io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter));
            log.info("Datadog OTel trace bridge exporter installed.");
        } catch (Exception e) {
            log.warn("Failed to hook up Datadog OTel tracer shim bridge: {}", e.getMessage(), e);
        }
    }

    /**
     * Hooks a {@link DdBridgeMetricExporter} into the meter provider so that OTel metrics
     * are replayed into DD's OTel MeterProvider shim for dual visibility.
     */
    private void hookUpDatadogMeterShim(SdkMeterProviderBuilder sdkMeterProviderBuilder) {
        try {
            ClassLoader ddClassLoader = getDatadogClassLoader();
            if (ddClassLoader == null) {
                log.warn("Datadog agent classloader is null — skipping DD metric bridge.");
                return;
            }

            Class<?> otelMpClass = Class.forName(
                    "datadog.opentelemetry.shim.metrics.OtelMeterProvider", true, ddClassLoader);
            MeterProvider ddMeterProvider = (MeterProvider) otelMpClass.getField("INSTANCE").get(null);

            var exporter = new DdBridgeMetricExporter(ddMeterProvider);
            sdkMeterProviderBuilder.registerMetricReader(
                    PeriodicMetricReader.builder(exporter)
                            .setInterval(Duration.ofSeconds(1))
                            .build());
            log.info("Datadog OTel metric bridge exporter installed.");
        } catch (Exception e) {
            log.warn("Failed to hook up Datadog OTel meter shim bridge: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets DD's agent classloader via reflection on the bootstrap Agent class.
     * Returns null if the DD agent hasn't initialized yet.
     */
    private static ClassLoader getDatadogClassLoader() throws Exception {
        Class<?> agentClass = Class.forName("datadog.trace.bootstrap.Agent");
        var field = agentClass.getDeclaredField("AGENT_CLASSLOADER");
        field.setAccessible(true);
        return (ClassLoader) field.get(null);
    }

}
