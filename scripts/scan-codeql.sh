#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "$2" != '--offline' ]; }; then
  echo "Usage: scan-codeql.sh <scan-directory> [--offline]" >&2
  exit 2
fi

CODEQL="${CODEQL_PATH:-codeql}"
if ! command -v "$CODEQL" >/dev/null 2>&1; then
  echo "CodeQL is required. Run 'mise install', then 'mise exec -- ./scripts/check-codeql.sh'." >&2
  exit 2
fi

SCAN_DIR="$1"
mkdir -p "$SCAN_DIR"

# Full-repository analysis: do not filter queries/results using a PR's diff.
# macOS system shells can break tracing, so invoke the wrapper's Java entry point.
BUILD_COMMAND='java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain compileJava --no-daemon --no-build-cache --rerun-tasks'
if [ "${2:-}" = '--offline' ]; then
  # Use the spec frozen by codeqlCacheKey, not a second network fetch during extraction.
  mkdir -p "$ROOT/braintrust-api/build/openapi"
  cp "$ROOT/build/codeql/spec-input/openapi/spec.yaml" "$ROOT/braintrust-api/build/openapi/spec.yaml"
  BUILD_COMMAND="$BUILD_COMMAND --offline -x :braintrust-api:fetchOpenApiSpec"
fi

"$CODEQL" database create "$SCAN_DIR/java-db" \
  --language=java \
  --source-root="$ROOT" \
  --command="$BUILD_COMMAND"

# A successful scan can contain findings. CI uploads those results just like a
# fresh scan; the local check-codeql.sh command separately fails on findings.
"$CODEQL" database analyze "$SCAN_DIR/java-db" \
  java-code-scanning.qls \
  --format=sarif-latest \
  --output="$SCAN_DIR/results.sarif" \
  --sarif-category='/language:java-kotlin' \
  --threads=0
