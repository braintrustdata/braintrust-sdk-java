package dev.braintrust.config;

import javax.annotation.Nullable;

public record SpanOriginEnvironment(@Nullable String type, @Nullable String name) {}
