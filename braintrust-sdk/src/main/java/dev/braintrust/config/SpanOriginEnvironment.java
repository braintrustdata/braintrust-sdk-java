package dev.braintrust.config;

import javax.annotation.Nullable;

public record SpanOriginEnvironment(String type, @Nullable String name) {}
