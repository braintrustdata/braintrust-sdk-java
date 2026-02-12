# Braintrust Java Agent — Auto-Instrumentation Feature

# FIXME -- don't merge this. make a proper AGENTS.md

## Overview

This document captures the design and implementation context for the Braintrust Java Agent (`braintrust-java-agent`), added on the `ark/autoinstrumentation` branch. The agent provides zero-code auto-instrumentation via `-javaagent:braintrust-java-agent.jar` — no SDK code changes required in user applications.

---

## Module Structure

```
braintrust-java-agent/
  bootstrap/            # Classes on the JVM bootstrap classpath (visible to all code)
  internal/             # Agent internals (hidden; loaded only by BraintrustClassLoader)
  instrumentation-api/  # InstrumentationModule / TypeInstrumentation framework + Muzzle
  instrumentation/
    openai/             # Auto-instrumentation for com.openai:openai-java (WIP)
  build.gradle          # Agent JAR assembly
buildSrc/
  src/main/groovy/dev/braintrust/gradle/muzzle/
                        # Gradle muzzle DSL plugin (MuzzlePlugin, MuzzleTask, MavenVersions, ...)
```

---

## Boot Sequence

1. JVM invokes `AgentBootstrap.premain()` (on system classloader)
2. Sets OTel system properties (`otel.java.global-autoconfigure.enabled=true`, exporters=`none`)
3. Appends agent JAR to bootstrap classpath via `Instrumentation.appendToBootstrapClassLoaderSearch()`
4. Creates `BraintrustClassLoader` (parent = platform CL) and registers it in `BraintrustBridge`
5. Reflectively calls `BraintrustAgent.install(agentArgs, inst)` through `BraintrustClassLoader`
6. `BraintrustAgent.install()` calls `Braintrust.get()` (fail-fast SDK config validation) then `InstrumentationInstaller.install()`
7. When user code first calls `GlobalOpenTelemetry.get()`, OTel autoconfigure fires `OtelAutoConfiguration.customize()`, which reflectively calls `BraintrustAgent.configureOpenTelemetry()` to inject Braintrust's span processor/exporter

---

## Classloader Isolation

```
JVM Bootstrap CL
  <- AgentBootstrap, BraintrustBridge, BraintrustClassLoader, OtelAutoConfiguration
  <- OTel API + SDK + autoconfigure JARs (full, unshaded)

BraintrustClassLoader  (parent = platform CL)
  <- Everything else: ByteBuddy, OTLP exporter, Braintrust SDK core,
     InstrumentationInstaller, all InstrumentationModules, advice classes
  <- Reads from internal/*.classdata entries in the agent JAR

App System CL  (parent = platform CL)
  <- User application code, OpenAI SDK, etc.
  <- Helper classes injected at transform time by HelperInjector
```

Agent internals are stored as `.classdata` files under `internal/` in the agent JAR — invisible to the normal JVM classloading delegation chain, only findable by `BraintrustClassLoader.findClass()`.

---

## Agent JAR Assembly (`braintrust-java-agent/build.gradle`)

Three Gradle configurations feed into the `agentJar` task:

| Configuration | Contents | How bundled |
|---|---|---|
| `bootstrap` | `braintrust-java-agent:bootstrap` project output | Normal `.class` files (flat in JAR root) |
| `bootstrapLibs` | OTel API + SDK + autoconfigure JARs | Normal `.class` files (flat in JAR root) |
| `internal` | `braintrust-java-agent:internal` shadow JAR | Renamed `.classdata` under `internal/` |

`META-INF/services/` entries are preserved so ServiceLoader works in both classloader contexts.

---

## Instrumentation Framework

Modeled after OpenTelemetry Java Instrumentation's module/type separation:

- **`InstrumentationModule`** — abstract base, discovered via `ServiceLoader`. Declares: module name, classloader pre-filter matcher, list of `TypeInstrumentation`s, helper class names.
- **`TypeInstrumentation`** — interface: `typeMatcher()` (ByteBuddy `ElementMatcher`) + `transform(TypeTransformer)`.
- **`TypeTransformerImpl`** — wires ByteBuddy `Advice.to(adviceClass, classFileLocator).on(methodMatcher)` using a `ClassFileLocator` backed by the agent classloader.
- **`HelperInjector`** — reads helper class bytes from agent CL, injects into app CL via `ClassInjector.UsingReflection`. Idempotent per (classloader, module) pair. WeakHashMap keys prevent CL leaks.
- **`InstrumentationInstaller`** — discovers modules via `ServiceLoader`, combines classloader matcher + `MuzzleCheck` as a pre-filter, applies transformers, calls `agentBuilder.installOn(inst)`.

---

## Muzzle — Binary Compatibility Safety System

Adapted from the Datadog Java agent. Prevents an instrumentation from activating when the target library version is incompatible.

### Build time (`MuzzleGenerator` / `generateMuzzle` Gradle task)

For each `InstrumentationModule` subclass:
1. Instantiates the module, collects advice class names via a no-op `AdviceCollector` `TypeTransformer`
2. ASM-scans advice bytecode (`ReferenceCreator`) via BFS, collecting all external class/method/field references
3. Generates a `$Muzzle` side-class (using ByteBuddy's ASM `ClassWriter`) with a static `create()` method that returns a pre-built `ReferenceMatcher`
4. Writes the `.class` file alongside the module's `.class` in the output directory

The `generateMuzzle` task runs after `compileJava` and before `classes`, so the `$Muzzle` file is included in the normal JAR.

### Runtime (`MuzzleCheck`)

`InstrumentationInstaller` loads the `$Muzzle` class from the agent CL and calls `create()`. Falls back to runtime ASM scanning if no `$Muzzle` exists. Results cached per classloader in a `ConcurrentHashMap`. Uses ByteBuddy's `TypePool` to inspect class bytes without loading/linking.

### Gradle muzzle plugin (`buildSrc`)

```groovy
muzzle {
  pass { group = 'com.openai'; module = 'openai-java'; versions = '[2.0.0,)' }
  fail { group = 'com.openai'; module = 'openai-java'; versions = '[0.1.0,2.0.0)' }
}
```

`MuzzleTask` fetches all matching versions from Maven Central (`maven-metadata.xml`), creates an isolated classloader per version, and validates that pass/fail assertions hold. The `muzzle` Gradle task must be run explicitly (not wired into `check`).

---

## OpenAI Instrumentation (WIP)

**Module:** `braintrust-java-agent/instrumentation/openai`
**Packages:**
- `dev.braintrust.agent.instrumentation.openai.auto` — `OpenAIInstrumentationModule`, `OpenAIOkHttpClientBuilderInstrumentation` (ByteBuddy advice)
- `dev.braintrust.agent.instrumentation.openai.manual` — `BraintrustOpenAI` (helper class injected into app CL)

**Target:** `com.openai.client.okhttp.OpenAIOkHttpClient$Builder.build()`

**Current state:** The advice is a stub — it calls `.hashCode()` and sets `BraintrustOpenAI.autoInstrumentationApplied = true`. No actual tracing is wired up yet.

**What needs to be implemented:**

The existing SDK (`src/main/java/dev/braintrust/instrumentation/openai/BraintrustOpenAI.java`) already provides a complete `wrapOpenAI(OpenTelemetry, OpenAIClient)` wrapper. The agent needs to call this automatically.

Two candidate approaches:

1. **Inject an OkHttp interceptor into the builder** (`OnMethodEnter` on `build()`): Add a tracing `okhttp3.Interceptor` to the builder before `build()` runs. This is the pattern both DD and OTel use for OkHttp instrumentation. Doesn't require swapping the return value.

2. **Replace the return value** (`OnMethodExit` on `build()` with `@Advice.Return(readOnly=false)`): Capture the built `OpenAIClient` and replace it with `BraintrustOpenAI.wrapOpenAI(GlobalOpenTelemetry.get(), client)`. Clean semantics but requires ByteBuddy return-value replacement support.

The helper class (`BraintrustOpenAI` in `manual/`) is injected into the app classloader. It can reference `GlobalOpenTelemetry` (on bootstrap) and whatever the core SDK exposes.

---

## Reference Implementations

- **Datadog Java Agent:** `~/prog/dd-trace-java` — muzzle generator pattern (separate `$Muzzle` side-class), `FieldBackedContextStores`, `DatadogClassLoader`
- **OpenTelemetry Java Instrumentation:** `~/prog/opentelemetry-java-instrumentation` — `InstrumentationModule`/`TypeInstrumentation` separation, `VirtualField<T,F>`, indy advice dispatch, `library/` + `javaagent/` sub-project split

---

## Key Design Decisions vs. DD/OTel

| Aspect | Braintrust Agent | Datadog | OTel |
|---|---|---|---|
| Agent CL | `BraintrustClassLoader` (SecureClassLoader) | `DatadogClassLoader` | `AgentClassLoader` (URLClassLoader) |
| Module unit | `InstrumentationModule` + `TypeInstrumentation` | Single class (module = instrumenter) | `InstrumentationModule` + `TypeInstrumentation` |
| Muzzle serialization | `$Muzzle` side-class | `$Muzzle` side-class | Methods inlined into module class |
| Context storage | N/A (uses OTel Context) | `FieldBackedContextStores` | `VirtualField<T,F>` |
| Advice dispatch | Inline (ByteBuddy default) | Inline | Inline or `invokedynamic` (indy) |
| Bootstrap CL contents | Agent bootstrap + full OTel API/SDK | Agent bootstrap only; OTel not involved | Agent bootstrap + OTel API only |
