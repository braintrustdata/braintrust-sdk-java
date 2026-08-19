# Custom Mustache Template Patches

These templates are forked from **openapi-generator v7.14.0** and contain targeted
patches to fix bugs or add functionality not present upstream. They live under
`src/generator/` (not `src/main/resources`) so they are only available at
code-generation time and do not appear on the runtime classpath or in the published JAR.

When upgrading the generator version, diff each file against the new upstream template:
- `modules/openapi-generator/src/main/resources/Java/libraries/native/anyof_model.mustache`
- `modules/openapi-generator/src/main/resources/Java/libraries/native/api.mustache`

---

## anyof_model.mustache

**Upstream:** `Java/libraries/native/anyof_model.mustache` @ v7.14.0

### Patch 1 — TypeReference for container variants in deserializer
The upstream deserializer calls `readValueAs(List.class)` / `readValueAs(Map.class)` for
container-typed anyOf variants, using the raw erased type. This causes silent
misclassification when two variants share the same raw type (e.g. `List<String>` vs
`List<Foo>`). Fixed by using `readValueAs(new TypeReference<List<Foo>>(){})` so Jackson
knows the element type and fails fast when elements don't match, allowing the deserializer
to fall through to the correct variant.

### Patch 2 — SchemaType enum
Added a `SchemaType` enum with one constant per anyOf variant. Constants are named from
the spec `title` field (titlecased) when present, falling back to `baseType` (the raw
erased class name). Each constant carries the full generic `dataType` as a `String` field.

### Patch 3 — getVariantType()
Added `getVariantType()` returning the `SchemaType` enum constant for the current
instance. The resolved type is stored in a `resolvedVariantType` field set during
deserialization, so same-erased variants (e.g. `List<String>` vs `List<Foo>`) are
correctly distinguished. Manually constructed instances must use the
`(SchemaType, Object)` constructor to set the type explicitly.

### Patch 4 — Single (SchemaType, Object) constructor
Replaced overloaded `(T o)` constructors (which produce a "duplicate method after
erasure" compile error when two variants share the same raw type) with a single
`(SchemaType type, Object o)` constructor that requires the caller to declare which
variant they're constructing.

### Patch 5 — Named typed getters
Renamed typed getter methods from `getanyOf0Instance()` / `getanyOf1Instance()` to use
the same identifier as the `SchemaType` enum constant (e.g. `getSystemInstance()`,
`getWeightedInstance()`), keeping the instance accessor API consistent with the enum.

### Note — `toUrlQueryString` is intentionally left unimplemented for anyOf models
The `toUrlQueryString(String prefix)` body iterates `{{#composedSchemas.oneOf}}`, which is always
empty in an *anyOf* model, so the method falls through to `return null`. This looks like a one-line
fix (swap the tag to `composedSchemas.anyOf`) but is not:

- anyOf variants have no `baseName`, so the generator emits synthetic `any_of_0` / `any_of_1`
  literals — the output would be `any_of_0=<uuid>` rather than `ids=<uuid>`.
- Container variants render as `getActualInstance() instanceof List<UUID>`, which is not legal
  Java (the same generics problem Patch 1 above fixes in the deserializer).

Query parameters are serialized in `api.mustache` instead (see its Patch 1), so nothing calls this
method from the api layer. Leaving it returning `null` is preferable to emitting either garbage
parameter names or code that does not compile.

---

## api.mustache

**Upstream:** `Java/libraries/native/api.mustache` @ v7.14.0

### Patch 1 — anyOf/oneOf model query parameters are serialized via `parameterToPairs`
In the `isExplode`/`!hasVars`/`isModel` branch, the upstream template serializes the parameter
with `{{paramName}}.toUrlQueryString()`. Two problems:

1. It calls the method unconditionally, so a `null` parameter (e.g. `ids` on any list endpoint)
   throws a `NullPointerException`.
2. More seriously, `toUrlQueryString()` takes no argument, so the wrapper has no way to know the
   parameter is named `ids` — and for anyOf models the method returns `null` outright (see the
   note under `anyof_model.mustache` below). The generated code then skipped the blank result and
   sent the request **unfiltered**, so callers got a successful response over the wrong result
   set rather than an error.

Fixed by null-guarding, unwrapping the matched variant with `getActualInstance()`, and handing it
to the existing `ApiClient.parameterToPairs` helpers — `("multi", name, collection)` for list
variants (repeated `ids=a&ids=b`, which is what the spec documents) and `(name, value)` for
scalars. This reuses the same pair machinery as every other query parameter instead of the
composed-model serialization path.

Covered by `QueryParameterSerializationTest`, which asserts against the built request URI.
