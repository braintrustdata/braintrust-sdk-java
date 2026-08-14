# Braintrust API client

The SDK ships a low-level HTTP client for the [Braintrust REST API](https://api.braintrust.dev).

> If you just want to run evals or trace AI calls, prefer
> [`dev.braintrust.eval.Eval`](../braintrust-sdk/src/main/java/dev/braintrust/eval) and
> `dev.braintrust.trace.BraintrustTracing`. Reach for the API client only when you need
> raw REST access.

The client is **generated code**. Every resource, method, and model comes from Braintrust's
public OpenAPI spec:

- Spec repo: <https://github.com/braintrustdata/braintrust-openapi>
- The exact commit we generate against is pinned as `braintrustOpenApiRef` in
  [`gradle.properties`](../gradle.properties).

## Basic usage

```java
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.api.ProjectsApi;
import dev.braintrust.openapi.model.CreateProject;
import dev.braintrust.openapi.model.Project;

var client = BraintrustOpenApiClient.of(BraintrustConfig.fromEnvironment());
var projects = new ProjectsApi(client);

// Create a project. Model classes use fluent setters (not a builder).
Project created = projects.postProject(
        new CreateProject().name("my-project").description("created from java"));

System.out.println(created.getId() + " " + created.getName());
```

### Runnable example

A complete, runnable example can be found in [`examples/api-client`](../examples/api-client).

Run it with `BRAINTRUST_API_KEY=sk-... ./gradlew :examples:api-client:run`.
