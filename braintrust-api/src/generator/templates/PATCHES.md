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

---

## api.mustache

**Upstream:** `Java/libraries/native/api.mustache` @ v7.14.0

### Patch 1 — Null-guard for anyOf model query parameters
The upstream template calls `{{paramName}}.toUrlQueryString()` unconditionally for
`isModel` params in the `isExplode/!hasVars` branch. When the parameter is `null`
(e.g. the `ids` parameter on list endpoints), this throws a `NullPointerException`.
Fixed by wrapping the call in `if ({{paramName}} != null)` and checking the result is
non-blank before adding it to the query string joiner.
